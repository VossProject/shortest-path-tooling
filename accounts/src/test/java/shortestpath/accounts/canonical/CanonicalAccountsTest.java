package shortestpath.accounts.canonical;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import org.junit.Test;
import shortestpath.accounts.Account;
import shortestpath.accounts.AccountCompiler;
import shortestpath.accounts.Unlock;

public class CanonicalAccountsTest {
    @Test
    public void everyProfileCompiles() {
        for (String name : CanonicalAccounts.NAMES) {
            AccountCompiler.compile(CanonicalAccounts.account(name));
        }
    }

    @Test
    public void canonicalProfilesDoNotAssumeDeclaredUnlocks() {
        for (String name : CanonicalAccounts.NAMES) {
            assertFalse(name, CanonicalAccounts.account(name).unlocks().stream()
                .anyMatch(unlock -> unlock.kind == Unlock.Kind.SETTING));
        }
    }

    @Test
    public void spiritTreesArePlantedProgressively() {
        assertEquals(0, CanonicalAccounts.account("early").plantedSpiritTrees().size());
        assertEquals(1, CanonicalAccounts.account("mid").plantedSpiritTrees().size());
        assertEquals(2, CanonicalAccounts.account("end").plantedSpiritTrees().size());
        assertEquals(5, CanonicalAccounts.account("maxed").plantedSpiritTrees().size());
    }

    @Test
    public void endAndMaxedHaveTheQuestCape() {
        for (String name : new String[] {"end", "maxed"}) {
            Account account = CanonicalAccounts.account(name);
            for (Quest quest : Quest.values()) {
                assertEquals(name + " " + quest, QuestState.FINISHED, account.questState(quest));
            }
        }
    }
}
