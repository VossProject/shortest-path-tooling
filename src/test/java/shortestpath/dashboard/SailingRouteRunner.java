package shortestpath.dashboard;

import java.util.List;
import shortestpath.pathfinder.PathStep;
import shortestpath.pathfinder.PathfinderConfig;
import shortestpath.scenarios.Scenario;

/**
 * Searches a route with the plugin's experimental sailing search, for scenarios with a sailing speed. The
 * implementation lives in {@code src/sailing/java}, which is only compiled when the plugin checkout has the
 * sailing search; {@link #find()} returns {@code null} otherwise.
 */
public interface SailingRouteRunner {
    /**
     * @param normalPath the normal search's path for the same route, to compare with
     */
    PathfinderDashboardModels.SailingRun run(PathfinderConfig config, Scenario scenario, List<PathStep> normalPath);

    static SailingRouteRunner find() {
        try {
            return (SailingRouteRunner) Class.forName("shortestpath.dashboard.SailingRouteRunnerImpl")
                .getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }
}
