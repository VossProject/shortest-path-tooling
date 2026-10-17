package shortestpath.profiles;

import java.util.Collection;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import shortestpath.accounts.ClientState;
import shortestpath.pathfinder.PathfinderConfig;
import shortestpath.pathfinder.TestWorldData;
import shortestpath.requirement.model.VarRequirement;
import shortestpath.transport.Transport;

/**
 * A {@link PathfinderConfig} for an account's {@link ClientState}, on the shared {@link TestWorldData}.
 * It answers quest states and the clock from the state, and skips varbit or varplayer transport
 * requirements when the settings bypass them. Quest states are answered here rather than by the
 * client because {@code getQuestState} runs in the search loop.
 */
public final class AccountPathfinderConfig extends PathfinderConfig {
    private final ClientState state;
    private final boolean bypassVarbitChecks;
    private final boolean bypassVarPlayerChecks;

    AccountPathfinderConfig(Client client, PluginSettings settings, ClientState state) {
        super(client, settings, TestWorldData.map(), TestWorldData.transports(),
            TestWorldData.destinations(), TestWorldData.filteredDestinations(),
            TestWorldData.bankRequirements());
        this.state = state;
        this.bypassVarbitChecks = settings.isBypassVarbitChecks();
        this.bypassVarPlayerChecks = settings.isBypassVarPlayerChecks();
    }

    @Override
    public QuestState getQuestState(Quest quest) {
        return state.questState(quest);
    }

    // The gate chain reads var requirements through the RequirementHooks
    // Collection/Map overloads, not the single-transport convenience path, so
    // every overload must carry the bypass or it silently never applies.
    @Override
    public boolean varbitChecks(Transport transport, long evaluationTimeMinutes) {
        return !bypassVarbitChecks && super.varbitChecks(transport, evaluationTimeMinutes);
    }

    @Override
    public boolean varbitChecks(Collection<VarRequirement> requirements, long evaluationTimeMinutes) {
        return !bypassVarbitChecks && super.varbitChecks(requirements, evaluationTimeMinutes);
    }

    @Override
    public boolean varbitChecks(Collection<VarRequirement> requirements,
            Map<Integer, Integer> values, long evaluationTimeMinutes) {
        return !bypassVarbitChecks && super.varbitChecks(requirements, values, evaluationTimeMinutes);
    }

    @Override
    public boolean varPlayerChecks(Transport transport, long evaluationTimeMinutes) {
        return !bypassVarPlayerChecks && super.varPlayerChecks(transport, evaluationTimeMinutes);
    }

    @Override
    public boolean varPlayerChecks(Collection<VarRequirement> requirements, long evaluationTimeMinutes) {
        return !bypassVarPlayerChecks && super.varPlayerChecks(requirements, evaluationTimeMinutes);
    }

    @Override
    public boolean varPlayerChecks(Collection<VarRequirement> requirements,
            Map<Integer, Integer> values, long evaluationTimeMinutes) {
        return !bypassVarPlayerChecks && super.varPlayerChecks(requirements, values, evaluationTimeMinutes);
    }

    @Override
    protected long currentTimeMinutes() {
        return state.nowMinutes() == null ? super.currentTimeMinutes() : state.nowMinutes();
    }

    /** The game time timed requirements are evaluated at. */
    public long evaluationTimeMinutes() {
        return currentTimeMinutes();
    }
}
