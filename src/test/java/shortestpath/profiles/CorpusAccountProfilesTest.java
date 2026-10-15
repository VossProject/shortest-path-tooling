package shortestpath.profiles;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import net.runelite.api.gameval.VarPlayerID;
import org.junit.Test;
import shortestpath.accounts.Account;
import shortestpath.accounts.AccountCompiler;
import shortestpath.accounts.ClientState;
import shortestpath.accounts.Diary;
import shortestpath.accounts.HeadlessClient;
import shortestpath.accounts.PlantedSpiritTree;
import shortestpath.accounts.Poh;
import shortestpath.accounts.Spellbook;
import shortestpath.accounts.Unlock;
import shortestpath.accounts.canonical.CanonicalAccounts;
import shortestpath.requirement.ClientPlayerStateSource;
import shortestpath.requirement.PlayerStateSource;

public class CorpusAccountProfilesTest {
    private static final String FIXTURE = "/profiles/account-profiles-fixture.json";

    @Test
    public void loadsFixture() {
        Map<String, Account> accounts = CorpusAccountProfiles.loadResource(FIXTURE);
        assertEquals(Set.of("early", "late-mini"), accounts.keySet());

        Account early = accounts.get("early");
        assertEquals(Long.valueOf(100_000_000L), early.nowMinutes());
        assertEquals(QuestState.FINISHED, early.questState(Quest.DRAGON_SLAYER_I));
        assertEquals(QuestState.NOT_STARTED, early.questState(Quest.RUM_DEAL));
        assertEquals(70, early.level(Skill.AGILITY));
        assertEquals(Diary.Tier.MEDIUM, early.diary(Diary.ARDOUGNE));
        assertEquals(Diary.Tier.NONE, early.diary(Diary.DESERT));
        assertTrue(early.unlocks().contains(Unlock.FAIRY_RINGS));
        assertTrue(early.unlocks().contains(Unlock.ARCHITECTURAL_ALLIANCE));
        assertEquals(Spellbook.STANDARD, early.spellbook());
        assertEquals(Integer.valueOf(100), early.bank().get(995));
        assertEquals(Integer.valueOf(5), early.equipment().get(1704));
        assertEquals(Integer.valueOf(1000), early.runePouch().get(554));
        assertNotNull(early.poh());
        assertEquals(Poh.Location.RIMMINGTON, early.poh().location);
        assertEquals(Poh.JewelleryBox.NONE, early.poh().jewelleryBox);
        assertEquals(java.util.List.of("Varrock Portal"), early.poh().portals);
        assertTrue(early.plantedSpiritTrees().isEmpty());
        assertNull(early.minigameTeleportUsedAt());
        assertEquals(Integer.valueOf(12), early.varbits().get(532));
        assertEquals(Integer.valueOf(99999979), early.varplayers().get(888));

        Account late = accounts.get("late-mini");
        assertEquals(QuestState.FINISHED, late.questState(Quest.SONG_OF_THE_ELVES));
        assertTrue(late.unlocks().contains(Unlock.TALE_OF_THE_RIGHTEOUS));
        assertEquals(327, late.questPoints());
        assertEquals(Integer.valueOf(2376), late.totalLevel());
        assertEquals(99, late.level(Skill.MAGIC));
        assertEquals(Diary.Tier.ELITE, late.diary(Diary.LUMBRIDGE_DRAYNOR));
        assertEquals(Spellbook.LUNAR, late.spellbook());
        assertEquals(Poh.JewelleryBox.ORNATE, late.poh().jewelleryBox);
        assertTrue(late.poh().mountedGlory);
        assertNull(late.poh().portals);
        assertEquals(Set.of(PlantedSpiritTree.FARMING_GUILD, PlantedSpiritTree.PORT_SARIM),
            late.plantedSpiritTrees());
        assertEquals(Long.valueOf(99_999_900L), late.minigameTeleportUsedAt());
        assertEquals(Integer.valueOf(1000), late.bank().get(8013));
    }

    @Test
    public void rejectsUnknownVersion() throws IOException {
        Path file = Files.createTempFile("account-profiles", ".json");
        Files.writeString(file, "{\"formatVersion\": 2, \"profiles\": {}}");
        try {
            CorpusAccountProfiles.load(file);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("formatVersion"));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    public void compilesToClientState() {
        Account early = CorpusAccountProfiles.loadResource(FIXTURE).get("early");
        ClientState state = AccountCompiler.compile(early);
        assertEquals(QuestState.FINISHED, state.questState(Quest.DRAGON_SLAYER_I));

        PlayerStateSource source = new ClientPlayerStateSource(HeadlessClient.of(state));
        assertEquals(10, source.varp(VarPlayerID.DRAGONQUEST));
        assertEquals(70, source.realSkillLevel(Skill.AGILITY));
    }

    @Test
    public void collidingNamesMatchCanonicalQuestStates() {
        Map<String, Account> accounts = CorpusAccountProfiles.loadResource(FIXTURE);
        // The fixture's "early" collides with a canonical account name; on the documented
        // parity subset — the quest-state map the adapter itself produces — the compiled
        // states must agree. Raw varbits legitimately differ (the corpus carries its own
        // routing-variable baseline), so they are not part of the comparison.
        for (String name : accounts.keySet()) {
            if (!CanonicalAccounts.NAMES.contains(name)) {
                continue;
            }
            ClientState fromCorpus = AccountCompiler.compile(accounts.get(name));
            ClientState canonical = AccountCompiler.compile(CanonicalAccounts.account(name));
            assertEquals(name, canonical.questStates(), fromCorpus.questStates());
        }
    }
}
