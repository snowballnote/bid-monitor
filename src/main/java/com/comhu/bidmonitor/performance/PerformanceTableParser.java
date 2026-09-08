package com.comhu.bidmonitor.performance;

import org.jsoup.Jsoup;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import static com.comhu.bidmonitor.performance.PerformanceModels.*;

/** Clipboard HTML preserves PPT cell boundaries; quoted TSV supports embedded newlines. */
@Component
public class PerformanceTableParser {
    private static final Pattern NUMBERED_ROW = Pattern.compile("[ ]*[0-9]+(?:[-.)][0-9]*)?[ ]*\\t");
    private record RawRow(List<String> cells, String error) { }

    public List<ParsedRow> parse(PasteInput input) {
        if (input == null || length(input.text()) + length(input.html()) > 2_000_000) {
            throw new IllegalArgumentException("붙여넣기는 200만 자 이내로 입력하세요.");
        }
        List<RawRow> rows = new ArrayList<>();
        if (input.html() != null && !input.html().isBlank()) {
            var table = Jsoup.parse(input.html()).selectFirst("table");
            if (table != null) {
                for (var row : table.select("tr")) {
                    var cells = row.children().stream().filter(cell -> cell.is("td, th")).toList();
                    boolean merged = cells.stream().anyMatch(cell ->
                            (!cell.attr("colspan").isEmpty() && !cell.attr("colspan").equals("1"))
                            || (!cell.attr("rowspan").isEmpty() && !cell.attr("rowspan").equals("1")));
                    rows.add(new RawRow(cells.stream().map(cell -> cell.text().replace('\u00a0', ' ').trim()).toList(),
                            merged ? "병합 셀을 확인하고 5개 열로 나누어 입력하세요." : null));
                }
            }
        }
        if (rows.isEmpty()) rows = tsv(input.text() == null ? "" : input.text());
        List<ParsedRow> result = new ArrayList<>();
        for (int index = 0; index < rows.size(); index++) {
            var row = rows.get(index);
            if (row.cells().stream().allMatch(String::isBlank)) continue;
            if (row.cells().size() == 5 && row.cells().getFirst().matches("번호|실적번호|실적 번호|No\\.?")) continue;
            result.add(new ParsedRow(index + 1, row.cells(), row.error()));
        }
        if (result.isEmpty()) throw new IllegalArgumentException("붙여넣을 실적 행이 없습니다.");
        if (result.size() > 500) throw new IllegalArgumentException("한 번에 500행까지 붙여넣을 수 있습니다.");
        return result;
    }

    private List<RawRow> tsv(String source) {
        String text = source.replace("\r\n", "\n").replace('\r', '\n');
        List<RawRow> rows = new ArrayList<>();
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            // Recover at a new numbered row instead of swallowing the rest of a malformed table.
            if (quoted && ch == '\n' && startsNumberedRow(text, i + 1)) {
                cells.add(clean(cell));
                rows.add(new RawRow(List.copyOf(cells), "닫히지 않은 따옴표를 확인하세요."));
                cells.clear(); cell.setLength(0); quoted = false;
                continue;
            }
            if (ch == '"' && (quoted || cell.isEmpty())) {
                if (quoted && i + 1 < text.length() && text.charAt(i + 1) == '"') { cell.append('"'); i++; }
                else quoted = !quoted;
            } else if (!quoted && (ch == '\t' || ch == '\n')) {
                // Continue a wrapped cell if there are still fewer than five columns.
                boolean nextRow = ch == '\n' && startsNumberedRow(text, i + 1);
                if (ch == '\n' && cells.size() < 4 && !nextRow) { cell.append(' '); continue; }
                cells.add(clean(cell)); cell.setLength(0);
                if (ch == '\n') { rows.add(new RawRow(List.copyOf(cells), null)); cells.clear(); }
            } else cell.append(ch);
        }
        cells.add(clean(cell));
        rows.add(new RawRow(List.copyOf(cells), quoted ? "닫히지 않은 따옴표를 확인하세요." : null));
        return rows;
    }

    private boolean startsNumberedRow(String text, int offset) {
        return NUMBERED_ROW.matcher(text).region(offset, text.length()).lookingAt();
    }
    private String clean(StringBuilder value) { return value.toString().replaceAll("\\s+", " ").trim(); }
    private int length(String value) { return value == null ? 0 : value.length(); }
}