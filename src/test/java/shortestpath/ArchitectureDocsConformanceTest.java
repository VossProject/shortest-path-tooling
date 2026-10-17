package shortestpath;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.junit.Test;

/**
 * Doc-conformance lint for the committed architecture design docs.
 *
 * <p>Every extraction pull request deep-links a boundary record in
 * {@code docs/architecture-survey.md}, so the records are a contract
 * reviewers click through to — a field silently dropped from a record is
 * invisible in prose review but breaks the fixed schema the completeness
 * check relies on. This lint encodes that schema mechanically: every
 * {@code ### } record between {@code ## Boundary records} and the next
 * {@code ## } heading must carry all eleven fixed rows, written as the
 * first cell of a record table ({@code | Responsibilities | ...}).
 *
 * <p>{@link #missingFields(List)} is factored out so
 * {@link #missingFieldIsFlagged()} can prove the check fires on a mutated
 * record — the doc on disk is never written to.
 *
 * <p>Run with {@code ./gradlew test --tests '*ArchitectureDocs*'}.
 */
public class ArchitectureDocsConformanceTest {

    private static final Path SURVEY = Paths.get("docs", "architecture-survey.md");
    private static final Path TARGET = Paths.get("docs", "architecture-target.md");
    private static final Path SEQUENCE = Paths.get("docs", "extraction-sequence.md");

    /**
     * The fixed record schema, in written order — a record whose field
     * legitimately has nothing still writes the row ("none"/"provisional"),
     * so presence of the row label is what is checked, never the content.
     */
    private static final List<String> REQUIRED_FIELDS = List.of(
        "Responsibilities",
        "Owned state",
        "Subscribed events",
        "Published facts",
        "Injected dependencies",
        "Consumers",
        "Killed seams",
        "Seam anchors",
        "Extraction PR",
        "Blast radius",
        "Known violations");

    /**
     * The census verified at write time; the floor keeps a record silently
     * removed from the section from passing unnoticed.
     */
    private static final int EXPECTED_RECORD_COUNT = 18;

    private static List<String> readLines(Path path) throws IOException {
        assertTrue("doc must exist: " + path, Files.isRegularFile(path));
        return Files.readAllLines(path, StandardCharsets.UTF_8);
    }

    /**
     * The lines between {@code ## <heading>} and the next {@code ## }
     * heading, exclusive — so a scan never bleeds into a sibling section.
     */
    private static List<String> section(List<String> lines, String heading) {
        int start = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).equals("## " + heading)) {
                start = i + 1;
                break;
            }
        }
        assertTrue("doc has no '## " + heading + "' section", start >= 0);
        int end = lines.size();
        for (int i = start; i < lines.size(); i++) {
            if (lines.get(i).startsWith("## ")) {
                end = i;
                break;
            }
        }
        return lines.subList(start, end);
    }

    /**
     * Split a section into {@code ### } records — key = record title,
     * value = the body lines up to the next {@code ### } or the end of the
     * section. Linked order so failure messages read top-to-bottom.
     */
    static Map<String, List<String>> splitRecords(List<String> sectionLines) {
        Map<String, List<String>> records = new LinkedHashMap<>();
        String current = null;
        for (String line : sectionLines) {
            if (line.startsWith("### ")) {
                current = line.substring(4).trim();
                records.put(current, new ArrayList<>());
            } else if (current != null) {
                records.get(current).add(line);
            }
        }
        return records;
    }

    /**
     * Whether {@code line} is a table row whose first cell is exactly
     * {@code field} — the {@code | Field name | content} shape the records
     * write.
     */
    static boolean isFieldRow(String line, String field) {
        return Pattern.compile("^\\s*\\|\\s*" + Pattern.quote(field) + "\\s*\\|")
            .matcher(line).find();
    }

    /**
     * The required field names absent as a leading table-cell label in one
     * record's body — empty when the record is schema-complete.
     */
    static List<String> missingFields(List<String> recordLines) {
        List<String> missing = new ArrayList<>();
        for (String field : REQUIRED_FIELDS) {
            boolean present = false;
            for (String line : recordLines) {
                if (isFieldRow(line, field)) {
                    present = true;
                    break;
                }
            }
            if (!present) {
                missing.add(field);
            }
        }
        return missing;
    }

    /**
     * {@code ```<lang>} fenced blocks — the content lines between the
     * fences, one entry per block in file order.
     */
    static List<List<String>> fencedBlocks(List<String> lines, String lang) {
        List<List<String>> blocks = new ArrayList<>();
        List<String> current = null;
        for (String line : lines) {
            if (line.trim().startsWith("```")) {
                if (current == null && line.trim().equals("```" + lang)) {
                    current = new ArrayList<>();
                } else if (current != null) {
                    blocks.add(current);
                    current = null;
                }
                continue;
            }
            if (current != null) {
                current.add(line);
            }
        }
        return blocks;
    }

    @Test
    public void everyBoundaryRecordCarriesTheFixedSchema() throws IOException {
        Map<String, List<String>> records =
            splitRecords(section(readLines(SURVEY), "Boundary records"));

        assertTrue("the Boundary records section must hold at least "
                + EXPECTED_RECORD_COUNT + " records, found " + records.size(),
            records.size() >= EXPECTED_RECORD_COUNT);

        List<String> failures = new ArrayList<>();
        for (Map.Entry<String, List<String>> record : records.entrySet()) {
            List<String> missing = missingFields(record.getValue());
            if (!missing.isEmpty()) {
                failures.add(record.getKey() + " missing: " + missing);
            }
        }
        assertTrue("boundary records missing fixed-schema fields: " + failures,
            failures.isEmpty());
    }

    /**
     * Red-direction proof: the same field-presence check must flag a record
     * that drops a row. Mutates the extracted record text in memory — the
     * committed doc is never touched.
     */
    @Test
    public void missingFieldIsFlagged() throws IOException {
        Map<String, List<String>> records =
            splitRecords(section(readLines(SURVEY), "Boundary records"));
        assertTrue("need at least one record to mutate", !records.isEmpty());

        Map.Entry<String, List<String>> first =
            records.entrySet().iterator().next();
        List<String> mutated = new ArrayList<>(first.getValue());
        boolean removed = mutated.removeIf(l -> isFieldRow(l, "Seam anchors"));
        assertTrue("record '" + first.getKey() + "' must carry a Seam anchors"
            + " row for the mutation to apply", removed);

        assertEquals("the mutated record must flag exactly the removed field",
            List.of("Seam anchors"), missingFields(mutated));

        // A record body with no schema table at all flags the whole schema.
        assertEquals("a record without the schema table flags every field",
            REQUIRED_FIELDS, missingFields(List.of("prose only, no table")));
    }

    /**
     * The target doc's two mermaid diagrams must stay machine-checkable:
     * a {@code subgraph} without its {@code end} or a non-ASCII glyph breaks
     * the render the diagrams exist for.
     */
    @Test
    public void targetDocMermaidBlocksAreWellFormed() throws IOException {
        List<List<String>> blocks = fencedBlocks(readLines(TARGET), "mermaid");
        assertTrue("expected at least two mermaid blocks (current and target"
            + " state), found " + blocks.size(), blocks.size() >= 2);

        for (int i = 0; i < blocks.size(); i++) {
            List<String> block = blocks.get(i);
            long subgraphs = block.stream()
                .filter(l -> l.trim().startsWith("subgraph")).count();
            long ends = block.stream()
                .filter(l -> l.trim().equals("end")).count();
            assertEquals("mermaid block " + (i + 1)
                + " has unbalanced subgraph/end counts", subgraphs, ends);

            for (String line : block) {
                for (int c = 0; c < line.length(); c++) {
                    assertTrue("mermaid block " + (i + 1) + " carries a"
                        + " non-ASCII character (renders unreliably): " + line,
                        line.charAt(c) < 128);
                }
            }
        }
    }

    /**
     * The sequence doc's ordering table is the extraction schedule — it must
     * keep its numbered rows and name the standalone enum row and the
     * dependency-rule lint gate the per-PR checklist enforces.
     */
    @Test
    public void sequenceDocOrdersExtractions() throws IOException {
        List<String> lines = readLines(SEQUENCE);
        List<String> ordering = section(lines, "Ordering");
        long rows = ordering.stream()
            .filter(l -> l.matches("^\\s*\\|\\s*\\d+\\s*\\|.*")).count();
        assertTrue("the ordering table must carry at least 12 numbered"
            + " extraction rows, found " + rows, rows >= 12);

        String text = String.join("\n", lines);
        assertTrue("sequence doc must name the BankVisitState mechanical row",
            text.contains("BankVisitState"));
        assertTrue("sequence doc must name the dependency-rule lint gate",
            text.contains("DependencyRule"));
    }
}
