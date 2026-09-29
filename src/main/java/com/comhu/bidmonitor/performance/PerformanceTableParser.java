package com.comhu.bidmonitor.performance;

import org.jsoup.Jsoup;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import static com.comhu.bidmonitor.performance.PerformanceModels.*;

/** Clipboard HTML preserves PPT cell boundaries; quoted TSV supports embedded newlines. */
@Component
public class PerformanceTableParser {
    private static final Pattern NUMBERED_ROW = Pattern.compile("[ ]*[0-9]+(?:[-.)][0-9]*)?[ ]*\\t");
    private record RawRow(List<String> cells, String error) { }
    private record HeaderColumns(int businessName, int client, int businessPeriod, int contractAmount) { }

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
        HeaderColumns headers = null;
        for (int index = 0; index < rows.size(); index++) {
            var row = rows.get(index);
            if (row.cells().stream().allMatch(String::isBlank)) continue;
            HeaderColumns detectedHeaders = headers(row.cells());
            if (detectedHeaders != null) {
                headers = detectedHeaders;
                continue;
            }
            List<String> fields = performanceFields(row.cells(), headers);
            result.add(new ParsedRow(index + 1, fields, row.error()));
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
                // Continue a wrapped cell if there are still fewer than four performance columns.
                boolean nextRow = ch == '\n' && startsNumberedRow(text, i + 1);
                if (ch == '\n' && cells.size() < 3 && !nextRow) { cell.append(' '); continue; }
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

    private HeaderColumns headers(List<String> cells) {
        int businessName = headerIndex(cells, "사업명");
        int client = headerIndex(cells, "발주기관", "발주처");
        int businessPeriod = headerIndex(cells, "사업기간");
        int contractAmount = headerIndex(cells, "사업금액", "계약금액");
        return businessName >= 0 && client >= 0 && businessPeriod >= 0 && contractAmount >= 0
                ? new HeaderColumns(businessName, client, businessPeriod, contractAmount)
                : null;
    }

    private int headerIndex(List<String> cells, String... names) {
        for (int index = 0; index < cells.size(); index++) {
            String header = cells.get(index).replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
            for (String name : names) {
                if (header.equals(name.toLowerCase(Locale.ROOT))) {
                    return index;
                }
            }
        }
        return -1;
    }

    private List<String> performanceFields(List<String> cells, HeaderColumns headers) {
        if (headers != null) {
            int lastRequired = Math.max(
                    Math.max(headers.businessName(), headers.client()),
                    Math.max(headers.businessPeriod(), headers.contractAmount())
            );
            if (cells.size() <= lastRequired) {
                return cells;
            }
            return List.of(
                    cells.get(headers.businessName()),
                    cells.get(headers.client()),
                    cells.get(headers.businessPeriod()),
                    cells.get(headers.contractAmount())
            );
        }
        if (cells.size() == 4) {
            if (cells.get(2).matches("(?s).*\\d{4}.*") && !cells.get(1).matches("(?s).*\\d{4}.*")) {
                return List.of(cells.get(0), cells.get(1), cells.get(2), cells.get(3));
            }
            return List.of(cells.get(0), cells.get(3), cells.get(1), cells.get(2));
        }
        if (cells.size() == 5) {
            return List.of(cells.get(1), cells.get(4), cells.get(2), cells.get(3));
        }
        return cells;
    }

    private String clean(StringBuilder value) { return value.toString().replaceAll("\\s+", " ").trim(); }
    private int length(String value) { return value == null ? 0 : value.length(); }
}
