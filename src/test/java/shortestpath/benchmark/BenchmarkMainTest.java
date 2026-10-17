package shortestpath.benchmark;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import shortestpath.WorldPointUtil;
import shortestpath.route.RouteCli;
import shortestpath.scenarios.CanonicalCorpus;
import shortestpath.scenarios.Observation;
import shortestpath.scenarios.Route;
import shortestpath.scenarios.Scenario;
import shortestpath.scenarios.ScenarioRunner;
import shortestpath.scenarios.Suites;

public class BenchmarkMainTest {
    private static Path CORPUS;

    @BeforeClass
    public static void requireCorpus() {
        CORPUS = CanonicalCorpus.dir();
    }

    @Test
    public void rejectsUnknownScenario() throws Exception {
        expectFailure(writeManifest("legacy", List.of(caseJson("canonical", "unknown-route/early", 0))));
    }

    @Test
    public void rejectsUnknownSuite() throws Exception {
        expectFailure(writeManifest("legacy", List.of(caseJson("no-such-suite", firstCanonical(), 0))));
    }

    @Test
    public void rejectsDuplicateCase() throws Exception {
        expectFailure(writeManifest("legacy", List.of(caseJson("canonical", firstCanonical(), 0),
            caseJson("canonical", firstCanonical(), 0))));
    }

    @Test
    public void rejectsIncompleteRepetitions() throws Exception {
        String other = "gps-natural-0012/maxed";
        expectFailure(writeManifest("legacy", List.of(caseJson("canonical", firstCanonical(), 0),
            caseJson("canonical", firstCanonical(), 1), caseJson("canonical", other, 0))));
    }

    @Test
    public void rejectsUnknownFieldsAndFormats() throws Exception {
        Path manifest = writeManifest("legacy", List.of(caseJson("canonical", firstCanonical(), 0)));
        JsonObject json = JsonParser.parseString(Files.readString(manifest)).getAsJsonObject();
        json.addProperty("format_version", 1);
        Files.writeString(manifest, new Gson().toJson(json), StandardCharsets.UTF_8);
        expectFailure(manifest);
        json.addProperty("format_version", 2);
        json.addProperty("synthetic_benchmark_time", 100000000L);
        Files.writeString(manifest, new Gson().toJson(json), StandardCharsets.UTF_8);
        expectFailure(manifest);
    }

    @Test
    public void executesOnlyExplicitCasesAndReportsJvmMetadata() throws Exception {
        Path manifest = writeManifest("legacy", List.of(caseJson("canonical", firstCanonical(), 0),
            caseJson("canonical", firstCanonical(), 1)));
        Path output = Files.createTempFile("benchmark-main-result", ".json");
        Path progress = Files.createTempFile("benchmark-main-progress", ".jsonl");
        System.setProperty("benchmark.runId", "java-test-run");
        System.setProperty("benchmark.progressFile", progress.toString());
        try {
            BenchmarkMain.run(manifest, output);
        } finally {
            System.clearProperty("benchmark.runId");
            System.clearProperty("benchmark.progressFile");
        }
        JsonObject result = JsonParser.parseString(Files.readString(output)).getAsJsonObject();
        Assert.assertEquals(2, result.get("format_version").getAsInt());
        Assert.assertEquals(2, result.getAsJsonArray("observations").size());
        JsonObject metadata = result.getAsJsonObject("execution_metadata");
        Assert.assertTrue(metadata.getAsJsonObject("runtime").get("java_version").getAsString().length() > 0);
        Assert.assertEquals(2, metadata.get("repetitions").getAsInt());
        JsonObject first = result.getAsJsonArray("observations").get(0).getAsJsonObject();
        Assert.assertEquals(0, first.get("repetition").getAsInt());
        Assert.assertEquals("canonical", first.get("suite").getAsString());
        Assert.assertEquals(firstCanonical(), first.get("scenario").getAsString());
        Assert.assertEquals("early", first.get("profile").getAsString());
        Assert.assertEquals(1, result.getAsJsonArray("observations").get(1)
            .getAsJsonObject().get("repetition").getAsInt());
        List<String> events = Files.readAllLines(progress);
        Assert.assertEquals(5, events.size());
        Assert.assertEquals("running", JsonParser.parseString(events.get(0)).getAsJsonObject()
            .get("phase").getAsString());
        Assert.assertEquals("case-complete", JsonParser.parseString(events.get(2)).getAsJsonObject()
            .get("type").getAsString());
    }

    /** A Java scenario runs with its own profile and overrides, as the route CLI and dashboard run it. */
    @Test
    public void runsJavaScenariosAsTheOtherFrontendsDo() throws Exception {
        String name = "GE centre walk (routing control)";
        Observation direct = new ScenarioRunner(ScenarioRunner.Backend.LEGACY).run(Suites.find("routing-issues", name));
        Path manifest = writeManifest("legacy", List.of(caseJson("routing-issues", name, 0)));
        manifest = withField(manifest, "warmup", warmup("canonical", firstCanonical()));
        JsonObject observation = onlyObservation(manifest);
        Assert.assertEquals("ok", observation.get("outcome").getAsString());
        Assert.assertEquals("UNIT_TEST", observation.get("profile").getAsString());
        Assert.assertEquals("routing-control", observation.get("category").getAsString());
        Assert.assertEquals(direct.getCost().intValue(), observation.get("path_cost").getAsInt());
        Assert.assertEquals(direct.getPathLength(), observation.get("path_length").getAsInt());
    }

    /** A run reports its memory, the routing world it searched, and each query's allocation. */
    @Test
    public void reportsMemoryWorldAndAllocation() throws Exception {
        Path manifest = writeManifest("astar", List.of(caseJson("canonical", firstCanonical(), 0)));
        Path output = Files.createTempFile("benchmark-main-result", ".json");
        System.setProperty("benchmark.runId", "java-test-run");
        try {
            BenchmarkMain.run(manifest, output);
        } finally {
            System.clearProperty("benchmark.runId");
        }
        JsonObject result = JsonParser.parseString(Files.readString(output)).getAsJsonObject();
        JsonObject metadata = result.getAsJsonObject("execution_metadata");
        JsonObject memory = metadata.getAsJsonObject("memory");
        Assert.assertTrue(memory.get("heap_after_prepare_bytes").getAsLong() > 0);
        Assert.assertTrue(memory.get("static_build_allocated_bytes").getAsLong() > 0);
        Assert.assertTrue(memory.get("peak_heap_bytes").getAsLong() >= memory.get("heap_after_prepare_bytes").getAsLong() / 2);
        JsonObject world = metadata.getAsJsonObject("world");
        Assert.assertTrue(world.get("transports").getAsInt() > 1000);
        Assert.assertTrue(world.getAsJsonObject("transports_by_type").has("TRANSPORT"));
        Assert.assertTrue(world.get("search_tiles").getAsInt() > world.get("routing_components").getAsInt());
        JsonObject observation = result.getAsJsonArray("observations").get(0).getAsJsonObject();
        Assert.assertTrue(observation.get("allocated_bytes").getAsLong() > 0);
    }

    @Test
    public void aStarSessionDefaultsToColdAndIsValidated() throws Exception {
        Assert.assertEquals(ScenarioRunner.AStarSession.COLD, BenchmarkMain.loadPlan(aStarManifest(null, false)).aStarSession);
        Assert.assertEquals(ScenarioRunner.AStarSession.ACCOUNT, BenchmarkMain.loadPlan(aStarManifest("account", false)).aStarSession);
        Assert.assertEquals(ScenarioRunner.AStarSession.TARGET, BenchmarkMain.loadPlan(aStarManifest("target", false)).aStarSession);
        expectFailure(aStarManifest("warm", false));
        Path legacy = writeManifest("legacy", List.of(caseJson("canonical", firstCanonical(), 0)));
        expectFailure(withField(legacy, "astar_session", new com.google.gson.JsonPrimitive("target")));
    }

    @Test
    public void aStarSessionModesPrepareWhatTheyDescribeWithAndWithoutWarmup() throws Exception {
        for (boolean warmup : new boolean[] {false, true}) {
            String label = warmup ? "with warmup" : "without warmup";
            JsonObject cold = onlyObservation(aStarManifest("cold", warmup));
            JsonObject account = onlyObservation(aStarManifest("account", warmup));
            JsonObject target = onlyObservation(aStarManifest("target", warmup));

            assertStages(label + ", cold", cold, false, false);
            assertStages(label + ", account", account, true, false);
            assertStages(label + ", target", target, true, true);
            Assert.assertEquals(label, 0, target.get("reverse_search_ns").getAsLong());
            Assert.assertEquals(label, cold.get("path_cost"), account.get("path_cost"));
            Assert.assertEquals(label, cold.get("path_cost"), target.get("path_cost"));
        }
    }

    /** Sessions stay per account and per target across a run with several of each. */
    @Test
    public void aStarSessionModesHoldAcrossTargetsAndAccounts() throws Exception {
        List<Route> routes = Route.load(CORPUS.resolve("corpus/routes-v1.json")).subList(0, 2);
        List<JsonObject> cases = new java.util.ArrayList<>();
        for (Route route : routes) {
            for (String profile : List.of("early", "maxed")) {
                cases.add(caseJson("canonical", route.getId() + "/" + profile, 0));
            }
        }
        Map<String, JsonElement> coldCosts = new HashMap<>();
        for (String session : List.of("cold", "account", "target")) {
            Path manifest = withField(writeManifest("astar", cases), "diagnostic", new com.google.gson.JsonPrimitive(true));
            manifest = withField(manifest, "astar_session", new com.google.gson.JsonPrimitive(session));
            for (JsonObject observation : observations(manifest)) {
                String key = observation.get("scenario").getAsString();
                assertStages(session + " " + key, observation, !session.equals("cold"), session.equals("target"));
                if (session.equals("cold")) {
                    coldCosts.put(key, observation.get("path_cost"));
                } else {
                    Assert.assertEquals(session + " " + key, coldCosts.get(key), observation.get("path_cost"));
                }
            }
        }
        Assert.assertEquals(4, coldCosts.size());
    }

    private static void assertStages(String label, JsonObject observation, boolean graphReused, boolean targetReused) {
        Assert.assertEquals(label + ": graph_reused", graphReused, observation.get("graph_reused").getAsBoolean());
        Assert.assertEquals(label + ": target_reused", targetReused, observation.get("target_reused").getAsBoolean());
    }

    /**
     * A route with {@code allowTransports: false} (no canonical route has one) plans the same in every
     * frontend: the route CLI by name and by id, the canonical suite and this adapter.
     */
    @Test
    public void walkingOnlyRouteRunsTheSameInEveryFrontend() throws Exception {
        // transport-heavy-0001/maxed takes a fairy ring when transports are allowed
        Route withTransports = Route.load(CORPUS.resolve("corpus/routes-v1.json")).stream()
            .filter(route -> route.getId().equals("transport-heavy-0001")).findFirst().orElseThrow();
        JsonObject transported = routeCli("transport-heavy-0001", "maxed", "--json");
        Assert.assertTrue(transported.getAsJsonArray("transports").size() > 0);

        Path corpus = Files.createTempDirectory("walking-only-corpus");
        Files.createDirectories(corpus.resolve("corpus"));
        Files.writeString(corpus.resolve("corpus/routes-v1.json"), "[{\"id\": \"walking-only-0001\", "
            + "\"name\": \"Walking only\", \"start\": " + point(withTransports.getStart()) + ", \"target\": "
            + point(withTransports.getTarget()) + ", \"allowTransports\": false}]", StandardCharsets.UTF_8);
        String previous = System.getProperty("corpus.dir");
        System.setProperty("corpus.dir", corpus.toString());
        try {
            Scenario scenario = Suites.find("canonical", "walking-only-0001/maxed");
            Assert.assertFalse(scenario.isAllowTransports());
            Observation suite = new ScenarioRunner(ScenarioRunner.Backend.LEGACY).run(scenario);
            Assert.assertTrue(suite.isReached());

            JsonObject named = routeCli("--route", "walking-only-0001", "--profile", "maxed", "--json");
            JsonObject byId = routeCli("walking-only-0001", "maxed", "--json");
            Assert.assertFalse(named.get("allowTransports").getAsBoolean());
            assertOnlyWalkingTransports(named);
            Assert.assertEquals(named.toString(), byId.toString());
            Assert.assertEquals(suite.getCost().intValue(), named.get("cost").getAsInt());
            Assert.assertTrue(named.get("cost").getAsInt() > transported.get("cost").getAsInt());

            Path manifest = writeManifest("legacy", List.of(caseJson("canonical", "walking-only-0001/maxed", 0)));
            Assert.assertEquals(suite.getCost().intValue(), onlyObservation(manifest).get("path_cost").getAsInt());
        } finally {
            if (previous == null) {
                System.clearProperty("corpus.dir");
            } else {
                System.setProperty("corpus.dir", previous);
            }
        }
    }

    /** Doors, stairs and the like ({@code TRANSPORT}) are part of walking; nothing else is. */
    private static void assertOnlyWalkingTransports(JsonObject result) {
        for (var transport : result.getAsJsonArray("transports")) {
            Assert.assertEquals(transport.toString(), "TRANSPORT",
                transport.getAsJsonObject().get("type").getAsString());
        }
    }

    private static JsonObject routeCli(String... args) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream previous = System.out;
        try {
            System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8.name()));
            RouteCli.main(args);
        } finally {
            System.setOut(previous);
        }
        return JsonParser.parseString(new String(output.toByteArray(), StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static String point(int packed) {
        return "[" + WorldPointUtil.unpackWorldX(packed) + ", " + WorldPointUtil.unpackWorldY(packed) + ", "
            + WorldPointUtil.unpackWorldPlane(packed) + "]";
    }

    private static Path aStarManifest(String session, boolean warmup) throws Exception {
        Path manifest = writeManifest("astar", List.of(caseJson("canonical", firstCanonical(), 0)));
        manifest = withField(manifest, "diagnostic", new com.google.gson.JsonPrimitive(true));
        if (warmup) {
            manifest = withField(manifest, "warmup", warmup("canonical", firstCanonical()));
        }
        if (session != null) {
            manifest = withField(manifest, "astar_session", new com.google.gson.JsonPrimitive(session));
        }
        return manifest;
    }

    private static JsonObject onlyObservation(Path manifest) throws Exception {
        List<JsonObject> observations = observations(manifest);
        Assert.assertEquals(1, observations.size());
        return observations.get(0);
    }

    private static List<JsonObject> observations(Path manifest) throws Exception {
        Path output = Files.createTempFile("benchmark-main-result", ".json");
        System.setProperty("benchmark.runId", "java-test-run");
        try {
            BenchmarkMain.run(manifest, output);
        } finally {
            System.clearProperty("benchmark.runId");
        }
        List<JsonObject> observations = new java.util.ArrayList<>();
        JsonParser.parseString(Files.readString(output)).getAsJsonObject().getAsJsonArray("observations")
            .forEach(observation -> observations.add(observation.getAsJsonObject()));
        return observations;
    }

    /** The first canonical route with the early profile, as a canonical scenario name. */
    private static String firstCanonical() throws Exception {
        return Route.load(CORPUS.resolve("corpus/routes-v1.json")).get(0).getId() + "/early";
    }

    private static JsonObject caseJson(String suite, String scenario, int repetition) {
        JsonObject result = new JsonObject();
        result.addProperty("suite", suite);
        result.addProperty("scenario", scenario);
        result.addProperty("repetition", repetition);
        return result;
    }

    private static com.google.gson.JsonArray warmup(String suite, String scenario) {
        JsonObject entry = new JsonObject();
        entry.addProperty("suite", suite);
        entry.addProperty("scenario", scenario);
        com.google.gson.JsonArray array = new com.google.gson.JsonArray();
        array.add(entry);
        return array;
    }

    private static Path writeManifest(String algorithm, List<JsonObject> cases) throws Exception {
        JsonObject result = new JsonObject();
        result.addProperty("format_version", 2);
        result.addProperty("algorithm", algorithm);
        com.google.gson.JsonArray array = new com.google.gson.JsonArray();
        cases.forEach(array::add);
        result.add("cases", array);
        Path path = Files.createTempFile("benchmark-manifest", ".json");
        Files.writeString(path, new Gson().toJson(result), StandardCharsets.UTF_8);
        return path;
    }

    private static Path withField(Path manifest, String field, JsonElement value) throws Exception {
        JsonObject json = JsonParser.parseString(Files.readString(manifest)).getAsJsonObject();
        json.add(field, value);
        Files.writeString(manifest, new Gson().toJson(json), StandardCharsets.UTF_8);
        return manifest;
    }

    private static void expectFailure(Path manifest) throws Exception {
        try {
            BenchmarkMain.loadPlan(manifest);
            Assert.fail("expected manifest rejection");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }
}
