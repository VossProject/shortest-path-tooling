package shortestpath.benchmark;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import shortestpath.pathfinder.AStarPathfinder;
import shortestpath.pathfinder.PathfinderResult;
import shortestpath.pathfinder.astar.AStarForwardSearch;
import shortestpath.pathfinder.astar.RoutingStatic;
import shortestpath.profiles.CompiledAccount;
import shortestpath.scenarios.Observation;
import shortestpath.scenarios.Scenario;
import shortestpath.scenarios.ScenarioRunner;
import shortestpath.scenarios.Suites;
import shortestpath.transport.Transport;
import shortestpath.transport.ResourceLoader;

/**
 * The benchmark entry point ({@code ./gradlew benchmark --args="--manifest M --output OUT"}), called by
 * collisionlab/osrs-benchmarks. A manifest lists scenarios by suite and name ({@link Suites}); each runs
 * exactly as the route CLI and the dashboard run it, through {@link ScenarioRunner}, and is judged by
 * its own expectations ({@link Observation#check()}).
 *
 * <p>The scenarios, routes and accounts all come from this tooling checkout, so the tooling and plugin
 * commits in the execution metadata identify everything a case ran with.
 */
public final class BenchmarkMain {
    private static final int FORMAT_VERSION = 2;

    private BenchmarkMain() { }

    public static void main(String[] args) throws Exception {
        Arguments arguments = Arguments.parse(args);
        run(arguments.manifest, arguments.output);
    }

    static void run(Path manifestPath, Path output) throws IOException {
        Plan plan = loadPlan(manifestPath);
        String runId = setting("BENCHMARK_RUN_ID", "benchmark.runId");
        if (runId == null) {
            throw new IllegalArgumentException("BENCHMARK_RUN_ID is required");
        }

        ScenarioRunner runner = new ScenarioRunner(plan.backend, plan.aStarSession);
        JsonObject memory = new JsonObject();
        long allocatedBeforePrepare = JvmMetrics.allocatedBytes();
        runner.prepare();
        long routingStaticBuildNanos = runner.getRoutingStaticBuildNanos();
        if (plan.backend == ScenarioRunner.Backend.ASTAR && allocatedBeforePrepare >= 0) {
            memory.addProperty("static_build_allocated_bytes", JvmMetrics.allocatedBytes() - allocatedBeforePrepare);
        }
        memory.addProperty("heap_after_prepare_bytes", JvmMetrics.retainedHeapBytes());

        if (!plan.warmup.isEmpty()) {
            progressPhase("warming up");
            for (Scenario scenario : plan.warmup) {
                try {
                    runner.run(scenario, scenario.compile(), ScenarioRunner.Profiling.OFF);
                } catch (RuntimeException ignored) {
                    // A warm-up scenario that fails is reported when it is measured, if it is.
                }
            }
        }

        List<JsonObject> observations = new ArrayList<>();
        progressPhase("running");
        long gcCount = JvmMetrics.gcCount();
        long gcMillis = JvmMetrics.gcMillis();
        for (Case current : plan.cases) {
            progressStart(current);
            JsonObject observation = execute(current, plan, runner, runId);
            observations.add(observation);
            progressComplete(observation);
        }
        memory.addProperty("run_gc_count", JvmMetrics.gcCount() - gcCount);
        memory.addProperty("run_gc_ms", JvmMetrics.gcMillis() - gcMillis);
        memory.addProperty("peak_heap_bytes", JvmMetrics.peakHeapBytes());
        long peakResident = JvmMetrics.peakResidentBytes();
        if (peakResident >= 0) {
            memory.addProperty("peak_resident_bytes", peakResident);
        }
        memory.addProperty("heap_after_run_bytes", JvmMetrics.retainedHeapBytes());
        memory.addProperty("max_heap_bytes", Runtime.getRuntime().maxMemory());

        JsonObject envelope = new JsonObject();
        envelope.addProperty("format_version", FORMAT_VERSION);
        JsonObject metadata = executionMetadata(plan, routingStaticBuildNanos);
        metadata.add("memory", memory);
        // Measured after the memory figures, so loading the transports again doesn't count in them.
        metadata.add("world", world(runner.getRoutingStatic()));
        envelope.add("execution_metadata", metadata);
        JsonArray rows = new JsonArray();
        observations.forEach(rows::add);
        envelope.add("observations", rows);
        Path absolute = output.toAbsolutePath();
        Files.createDirectories(absolute.getParent());
        Files.writeString(absolute, new GsonBuilder().serializeNulls().create().toJson(envelope),
            StandardCharsets.UTF_8);
    }

    private static String setting(String environment, String property) {
        String value = System.getenv(environment);
        if (value == null || value.isBlank()) {
            value = System.getProperty(property);
        }
        return value == null || value.isBlank() ? null : value;
    }

    private static void progressPhase(String phase) throws IOException {
        JsonObject event = new JsonObject();
        event.addProperty("type", "phase");
        event.addProperty("phase", phase);
        appendProgress(event);
    }

    private static void progressStart(Case current) throws IOException {
        JsonObject event = new JsonObject();
        event.addProperty("type", "case-start");
        event.addProperty("suite", current.suite);
        event.addProperty("scenario", current.scenario.getName());
        event.addProperty("repetition", current.repetition);
        appendProgress(event);
    }

    private static void progressComplete(JsonObject observation) throws IOException {
        JsonObject event = new JsonObject();
        event.addProperty("type", "case-complete");
        event.add("observation", observation);
        appendProgress(event);
    }

    private static void appendProgress(JsonObject event) throws IOException {
        String progress = setting("BENCHMARK_PROGRESS_FILE", "benchmark.progressFile");
        if (progress == null) return;
        Path path = Path.of(progress).toAbsolutePath();
        Files.createDirectories(path.getParent());
        Files.writeString(path, event + "\n", StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /**
     * Reads and checks a format-2 manifest:
     *
     * <pre>{@code
     * {"format_version": 2, "algorithm": "legacy" | "astar",
     *  "astar_session": "cold" | "account" | "target",   (optional, astar only; default cold)
     *  "diagnostic": false,                               (optional: search counters and phase timings)
     *  "warmup": [{"suite": S, "scenario": NAME}, ...],   (run once each before measuring; not reported)
     *  "cases": [{"suite": S, "scenario": NAME, "repetition": N}, ...]}
     * }</pre>
     *
     * Every scenario in {@code cases} must have repetitions 0 to R-1, the same R for all.
     */
    static Plan loadPlan(Path manifestPath) throws IOException {
        JsonObject manifest = JsonParser.parseString(
            Files.readString(manifestPath, StandardCharsets.UTF_8)).getAsJsonObject();
        Set<String> known = Set.of("format_version", "algorithm", "astar_session", "diagnostic", "warmup", "cases");
        for (String key : manifest.keySet()) {
            if (!known.contains(key)) {
                throw new IllegalArgumentException("unknown manifest field: " + key);
            }
        }
        if (requireNonNegativeInt(manifest, "format_version") != FORMAT_VERSION) {
            throw new IllegalArgumentException("manifest format_version must be " + FORMAT_VERSION);
        }
        ScenarioRunner.Backend backend = ScenarioRunner.Backend.parse(requireString(manifest, "algorithm"));
        ScenarioRunner.AStarSession aStarSession = manifest.has("astar_session")
            ? ScenarioRunner.AStarSession.parse(requireString(manifest, "astar_session"))
            : ScenarioRunner.AStarSession.COLD;
        if (backend != ScenarioRunner.Backend.ASTAR && manifest.has("astar_session")) {
            throw new IllegalArgumentException("astar_session requires the astar algorithm");
        }
        boolean diagnostic = manifest.has("diagnostic") && requireBoolean(manifest, "diagnostic");

        ScenarioIndex index = new ScenarioIndex();
        List<Scenario> warmup = new ArrayList<>();
        if (manifest.has("warmup")) {
            for (JsonElement element : requiredArray(manifest, "warmup")) {
                JsonObject raw = element.getAsJsonObject();
                warmup.add(index.get(requireString(raw, "suite"), requireString(raw, "scenario")));
            }
        }

        JsonArray rawCases = requiredArray(manifest, "cases");
        if (rawCases.size() == 0) {
            throw new IllegalArgumentException("manifest cases must not be empty");
        }
        List<Case> cases = new ArrayList<>();
        Map<String, Set<Integer>> repetitions = new LinkedHashMap<>();
        int maxRepetition = 0;
        for (JsonElement element : rawCases) {
            JsonObject raw = element.getAsJsonObject();
            String suite = requireString(raw, "suite");
            Scenario scenario = index.get(suite, requireString(raw, "scenario"));
            int repetition = requireNonNegativeInt(raw, "repetition");
            String key = suite + "\n" + scenario.getName();
            if (!repetitions.computeIfAbsent(key, ignored -> new HashSet<>()).add(repetition)) {
                throw new IllegalArgumentException("duplicate manifest case: " + suite + " / "
                    + scenario.getName() + " repetition " + repetition);
            }
            maxRepetition = Math.max(maxRepetition, repetition);
            cases.add(new Case(suite, scenario, repetition));
        }
        for (Map.Entry<String, Set<Integer>> entry : repetitions.entrySet()) {
            if (entry.getValue().size() != maxRepetition + 1) {
                throw new IllegalArgumentException("manifest repetitions are incomplete: "
                    + entry.getKey().replace('\n', '/'));
            }
        }
        return new Plan(cases, warmup, maxRepetition + 1, diagnostic, backend, aStarSession);
    }

    /**
     * The routing world this run searched: the plugin's transports, and for A* the size of its static
     * routing data (walkable search tiles, sites, routing components, crossings, sparse graph).
     */
    static JsonObject world(RoutingStatic routing) {
        JsonObject world = new JsonObject();
        Map<Integer, Set<Transport>> transports = ResourceLoader.transports();
        Map<String, Integer> byType = new java.util.TreeMap<>();
        int total = 0;
        for (Set<Transport> atOrigin : transports.values()) {
            for (Transport transport : atOrigin) {
                byType.merge(String.valueOf(transport.getType()), 1, Integer::sum);
                total++;
            }
        }
        world.addProperty("transports", total);
        world.addProperty("transport_origins", transports.size());
        JsonObject types = new JsonObject();
        byType.forEach(types::addProperty);
        world.add("transports_by_type", types);
        if (routing != null) {
            world.addProperty("search_tiles", routing.searchTileCount());
            world.addProperty("sites", routing.siteCount());
            world.addProperty("routing_components", routing.routingComponentCount());
            world.addProperty("reachable_banks", routing.reachableBankCount());
            world.addProperty("crossings", routing.crossingCount());
            world.addProperty("sparse_vertices", routing.sparseVertexCount());
            world.addProperty("sparse_original_vertices", routing.sparseOriginalCount());
            world.addProperty("sparse_steiner_vertices", routing.sparseSteinerCount());
            world.addProperty("sparse_edges", routing.sparseAdjacencyCount());
        }
        return world;
    }

    /** Scenarios by suite and exact name, each suite loaded once. */
    private static final class ScenarioIndex {
        private final Map<String, Map<String, Scenario>> suites = new HashMap<>();

        Scenario get(String suite, String name) throws IOException {
            Map<String, Scenario> scenarios = suites.get(suite);
            if (scenarios == null) {
                scenarios = new HashMap<>();
                for (Scenario scenario : Suites.load(suite)) {
                    scenarios.put(scenario.getName(), scenario);
                }
                suites.put(suite, scenarios);
            }
            Scenario scenario = scenarios.get(name);
            if (scenario == null) {
                throw new IllegalArgumentException("suite " + suite + " has no scenario named '" + name + "'");
            }
            return scenario;
        }
    }

    private static JsonObject executionMetadata(Plan plan, long routingStaticBuildNanos) {
        JsonObject metadata = new JsonObject();
        metadata.addProperty("algorithm", plan.backend.id());
        if (plan.backend == ScenarioRunner.Backend.ASTAR) {
            metadata.addProperty("astar_session", plan.aStarSession.id());
        }
        metadata.addProperty("diagnostic", plan.diagnostic);
        metadata.addProperty("repetitions", plan.repetitions);
        metadata.addProperty("warmup_scenarios", plan.warmup.size());
        JsonObject runtime = new JsonObject();
        runtime.addProperty("java_version", System.getProperty("java.version"));
        runtime.addProperty("java_vendor", System.getProperty("java.vendor"));
        runtime.addProperty("vm_name", System.getProperty("java.vm.name"));
        runtime.addProperty("vm_version", System.getProperty("java.vm.version"));
        runtime.addProperty("available_processors", Runtime.getRuntime().availableProcessors());
        runtime.addProperty("max_heap_bytes", Runtime.getRuntime().maxMemory());
        metadata.add("runtime", runtime);
        JsonObject dependencies = new JsonObject();
        dependencies.add("shortest_path", dependency("benchmark.shortestPathCommit", "benchmark.shortestPathDirty"));
        dependencies.add("shortest_path_tooling", dependency("benchmark.toolingCommit", "benchmark.toolingDirty"));
        metadata.add("dependencies", dependencies);
        if (routingStaticBuildNanos > 0) {
            metadata.addProperty("routing_static_build_ms", routingStaticBuildNanos / 1_000_000L);
        }
        return metadata;
    }

    private static JsonObject dependency(String commitProperty, String dirtyProperty) {
        JsonObject result = new JsonObject();
        result.addProperty("commit", System.getProperty(commitProperty, "unknown"));
        result.addProperty("dirty", Boolean.parseBoolean(System.getProperty(dirtyProperty, "false")));
        return result;
    }

    /**
     * Runs one case. The account is compiled before the timed search; {@code total_ns} is the search
     * alone ({@link Observation#getTotalNanos()}).
     */
    private static JsonObject execute(Case current, Plan plan, ScenarioRunner runner, String runId) {
        JsonObject row = baseObservation(current, runId);
        CompiledAccount account;
        try {
            account = current.scenario.compile();
        } catch (RuntimeException exception) {
            return failure(row, "account: " + describe(exception));
        }
        try {
            long allocatedBefore = JvmMetrics.allocatedBytes();
            Observation observation = runner.run(current.scenario, account, ScenarioRunner.Profiling.OFF);
            long allocated = allocatedBefore < 0 ? -1 : JvmMetrics.allocatedBytes() - allocatedBefore;
            PathfinderResult result = observation.getResult();
            Observation.Assertion check = observation.check();
            row.addProperty("outcome", Boolean.FALSE.equals(check.passed) ? "unexpected" : "ok");
            row.addProperty("check", check.message == null ? "" : check.message);
            row.addProperty("reachable", observation.isReached());
            addNullable(row, "path_cost", observation.getCost());
            row.addProperty("path_length", observation.getPathLength());
            row.addProperty("total_ns", observation.getTotalNanos());
            if (allocated >= 0) {
                row.addProperty("allocated_bytes", allocated);
            } else {
                row.add("allocated_bytes", JsonNull.INSTANCE);
            }
            row.addProperty("termination_reason", result.getTerminationReason() == null
                ? "" : result.getTerminationReason().name());
            row.addProperty("failure", "");
            if (plan.diagnostic) {
                addDiagnostics(row, observation, runner);
            }
            return row;
        } catch (RuntimeException exception) {
            return failure(row, describe(exception));
        }
    }

    private static void addDiagnostics(JsonObject row, Observation observation, ScenarioRunner runner) {
        PathfinderResult result = observation.getResult();
        AStarPathfinder aStar = observation.getAStar();
        row.addProperty("forward_search_ns", aStar == null ? result.getElapsedNanos() : aStar.getForwardSearchNanos());
        row.addProperty("states_popped", result.getNodesChecked());
        row.addProperty("transports_checked", result.getTransportsChecked());
        if (aStar == null) {
            return;
        }
        AStarForwardSearch.Counters counters = aStar.getAStarStats();
        row.addProperty("account_prepare_ns", aStar.getAccountPrepareNanos() + aStar.getGraphPrepareNanos());
        row.addProperty("graph_prepare_ns", aStar.getGraphPrepareNanos());
        row.addProperty("reverse_search_ns", aStar.getReverseSearchNanos());
        row.addProperty("seed_table_ns", aStar.getHeuristicPrepareNanos());
        row.addProperty("heuristic_prepare_ns", aStar.getReverseSearchNanos() + aStar.getHeuristicPrepareNanos());
        row.addProperty("astar_session", runner.getAStarSession().id());
        row.addProperty("graph_reused", aStar.isGraphReused());
        row.addProperty("target_reused", aStar.isTargetReused());
        row.addProperty("unique_states_reached", counters.uniqueStatesReached());
        row.addProperty("pq_pushes", counters.pqPushes());
        row.addProperty("stale_entries", counters.staleEntries());
        row.addProperty("max_queue_size", counters.maxQueueSize());
        row.addProperty("walking_pq_pushes", counters.walkingPqPushes());
        row.addProperty("local_transport_pq_pushes", counters.localTransportPqPushes());
        row.addProperty("global_pq_pushes", counters.globalPqPushes());
        row.addProperty("banking_pq_pushes", counters.bankingPqPushes());
        row.addProperty("transport_candidates", counters.transportCandidates());
        row.addProperty("transport_relaxations", counters.transportRelaxations());
        row.addProperty("successful_transport_relaxations", counters.successfulTransportRelaxations());
        row.addProperty("initial_capability", counters.initialCapability());
        row.addProperty("normal_heuristic_enabled_at_start", counters.normalHeuristicEnabledAtStart());
        row.addProperty("restricted_heuristic_states", counters.restrictedHeuristicStates());
        row.addProperty("restricted_heuristic_zeroes", counters.restrictedHeuristicZeroes());
        row.addProperty("normal_heuristic_states", counters.normalHeuristicStates());
    }

    private static String describe(RuntimeException exception) {
        String message = exception.getMessage();
        return exception.getClass().getName() + (message == null || message.isBlank() ? "" : ": " + message);
    }

    /** The query threw: no result fields, just why. */
    private static JsonObject failure(JsonObject row, String reason) {
        row.addProperty("outcome", "failed");
        row.addProperty("check", "");
        row.add("reachable", JsonNull.INSTANCE);
        row.add("path_cost", JsonNull.INSTANCE);
        row.add("path_length", JsonNull.INSTANCE);
        row.add("total_ns", JsonNull.INSTANCE);
        row.add("allocated_bytes", JsonNull.INSTANCE);
        row.addProperty("termination_reason", "");
        row.addProperty("failure", reason);
        return row;
    }

    private static JsonObject baseObservation(Case current, String runId) {
        Scenario scenario = current.scenario;
        JsonObject row = new JsonObject();
        row.addProperty("run_id", runId);
        row.addProperty("suite", current.suite);
        row.addProperty("scenario", scenario.getName());
        row.addProperty("category", scenario.getCategory());
        row.addProperty("profile", scenario.getProfile().name());
        row.addProperty("repetition", current.repetition);
        row.addProperty("expected_reachable", scenario.isExpectedReachable());
        return row;
    }

    private static void addNullable(JsonObject object, String field, Integer value) {
        if (value == null) {
            object.add(field, JsonNull.INSTANCE);
        } else {
            object.addProperty(field, value);
        }
    }

    private static JsonArray requiredArray(JsonObject object, String field) {
        if (!object.has(field) || !object.get(field).isJsonArray()) {
            throw new IllegalArgumentException("manifest field must be an array: " + field);
        }
        return object.getAsJsonArray(field);
    }

    private static String requireString(JsonObject object, String field) {
        if (!object.has(field) || !object.get(field).isJsonPrimitive()
                || !object.get(field).getAsJsonPrimitive().isString()
                || object.get(field).getAsString().isBlank()) {
            throw new IllegalArgumentException("manifest field must be a non-empty string: " + field);
        }
        return object.get(field).getAsString();
    }

    private static boolean requireBoolean(JsonObject object, String field) {
        if (!object.has(field) || !object.get(field).isJsonPrimitive()
                || !object.get(field).getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException("manifest field must be a JSON boolean: " + field);
        }
        return object.get(field).getAsBoolean();
    }

    private static int requireNonNegativeInt(JsonObject object, String field) {
        if (!object.has(field) || !object.get(field).isJsonPrimitive()
                || !object.get(field).getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("manifest field must be an integer: " + field);
        }
        try {
            int value = Integer.parseInt(object.get(field).getAsString());
            if (value < 0) {
                throw new IllegalArgumentException("manifest field must be non-negative: " + field);
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("manifest field must be an integer: " + field, exception);
        }
    }

    static final class Plan {
        final List<Case> cases;
        final List<Scenario> warmup;
        final int repetitions;
        final boolean diagnostic;
        final ScenarioRunner.Backend backend;
        final ScenarioRunner.AStarSession aStarSession;

        Plan(List<Case> cases, List<Scenario> warmup, int repetitions, boolean diagnostic,
                ScenarioRunner.Backend backend, ScenarioRunner.AStarSession aStarSession) {
            this.cases = List.copyOf(cases);
            this.warmup = List.copyOf(warmup);
            this.repetitions = repetitions;
            this.diagnostic = diagnostic;
            this.backend = backend;
            this.aStarSession = aStarSession;
        }
    }

    static final class Case {
        final String suite;
        final Scenario scenario;
        final int repetition;

        Case(String suite, Scenario scenario, int repetition) {
            this.suite = suite;
            this.scenario = scenario;
            this.repetition = repetition;
        }
    }

    private static final class Arguments {
        final Path manifest;
        final Path output;

        private Arguments(Path manifest, Path output) {
            this.manifest = manifest;
            this.output = output;
        }

        static Arguments parse(String[] args) {
            Path manifest = null;
            Path output = null;
            for (int i = 0; i < args.length; i++) {
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException("missing value for " + args[i]);
                }
                Path value = Path.of(args[++i]);
                switch (args[i - 1]) {
                    case "--manifest": manifest = value; break;
                    case "--output": output = value; break;
                    default: throw new IllegalArgumentException("unknown argument: " + args[i - 1]);
                }
            }
            if (manifest == null || output == null) {
                throw new IllegalArgumentException("usage: --manifest FILE --output FILE");
            }
            return new Arguments(manifest, output);
        }
    }
}
