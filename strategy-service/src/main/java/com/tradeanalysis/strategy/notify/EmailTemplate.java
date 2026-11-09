package com.tradeanalysis.strategy.notify;

import java.util.ArrayList;
import java.util.List;

/**
 * The one layout every account email uses: a branded header, a headline, a greeting, paragraphs, a details table, an
 * optional highlighted box (admin remarks, warnings), a call-to-action button and a footer. Builds both the HTML and
 * the plain-text version from the same content, so the two always say the same thing.
 */
final class EmailTemplate {

    enum Tone { INFO, SUCCESS, WARNING, DANGER }

    private static final String BRAND = "SysGrow";
    private static final String TAGLINE = "Trade Analysis Platform";

    private final String subject;
    private Tone tone = Tone.INFO;
    private String headline;
    private String greeting;
    private final List<Block> blocks = new ArrayList<>();
    private String actionLabel, actionUrl;
    private String footnote;

    private sealed interface Block permits Para, Details, Box, Bullets {}
    private record Para(String text) implements Block {}
    private record Details(List<String[]> rows) implements Block {}
    private record Box(String title, String text, Tone tone) implements Block {}
    private record Bullets(List<String> items) implements Block {}

    private EmailTemplate(String subject) { this.subject = subject; }

    static EmailTemplate subject(String subject) { return new EmailTemplate(subject); }

    EmailTemplate tone(Tone t)             { this.tone = t; return this; }
    EmailTemplate headline(String h)       { this.headline = h; return this; }
    EmailTemplate greeting(String name)    { this.greeting = "Hi " + name + ","; return this; }
    EmailTemplate para(String text)        { blocks.add(new Para(text)); return this; }
    EmailTemplate bullets(List<String> items) { if (!items.isEmpty()) blocks.add(new Bullets(List.copyOf(items))); return this; }
    /** Key/value rows ("Plan: GOLD"); rows with a null value are left out. */
    EmailTemplate details(String... keyValues) {
        List<String[]> rows = new ArrayList<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2)
            if (keyValues[i + 1] != null) rows.add(new String[]{keyValues[i], keyValues[i + 1]});
        if (!rows.isEmpty()) blocks.add(new Details(rows));
        return this;
    }
    /** A highlighted box; skipped when the text is blank. */
    EmailTemplate box(String title, String text, Tone t) {
        if (text != null && !text.isBlank()) blocks.add(new Box(title, text.trim(), t));
        return this;
    }
    EmailTemplate action(String label, String url) { this.actionLabel = label; this.actionUrl = url; return this; }
    EmailTemplate footnote(String text)    { this.footnote = text; return this; }

    UserEmails.Message build() { return new UserEmails.Message(subject, text(), html()); }

    // ---- plain text ----

    private String text() {
        StringBuilder b = new StringBuilder();
        if (greeting != null) b.append(greeting).append("\n\n");
        for (Block block : blocks) {
            switch (block) {
                case Para p -> b.append(p.text()).append("\n\n");
                case Bullets l -> { l.items().forEach(i -> b.append("• ").append(i).append('\n')); b.append('\n'); }
                case Details d -> { d.rows().forEach(r -> b.append(r[0]).append(": ").append(r[1]).append('\n')); b.append('\n'); }
                case Box x -> b.append(x.title()).append(":\n").append(x.text()).append("\n\n");
            }
        }
        if (actionUrl != null) b.append(actionLabel).append(": ").append(actionUrl).append("\n\n");
        if (footnote != null) b.append(footnote).append("\n\n");
        b.append("— ").append(BRAND).append(" · ").append(TAGLINE);
        return b.toString();
    }

    // ---- HTML (table layout and inline styles, which every mail client renders) ----

    private static String color(Tone t) {
        return switch (t) { case SUCCESS -> "#12805c"; case WARNING -> "#b7791f"; case DANGER -> "#c93c37"; case INFO -> "#1f6feb"; };
    }
    private static String soft(Tone t) {
        return switch (t) { case SUCCESS -> "#e3f5ee"; case WARNING -> "#fdf3e1"; case DANGER -> "#fdeceb"; case INFO -> "#e7f0ff"; };
    }

    private String html() {
        String accent = color(tone);
        StringBuilder b = new StringBuilder(4096);
        b.append("<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
         .append("<title>").append(esc(subject)).append("</title></head>")
         .append("<body style=\"margin:0;padding:0;background:#f6f7f9;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;color:#1c2430;\">")
         .append("<span style=\"display:none;max-height:0;overflow:hidden;opacity:0;\">").append(esc(headline != null ? headline : subject)).append("</span>")
         .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"background:#f6f7f9;padding:24px 12px;\"><tr><td align=\"center\">")
         .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"max-width:560px;background:#ffffff;border:1px solid #e3e6eb;border-radius:10px;overflow:hidden;\">")
         // header
         .append("<tr><td style=\"background:#0f2747;padding:16px 24px;\">")
         .append("<span style=\"font-size:17px;font-weight:700;color:#ffffff;letter-spacing:.2px;\">").append(BRAND).append("</span>")
         .append("<span style=\"font-size:12px;color:#9fb3cf;padding-left:8px;\">").append(TAGLINE).append("</span>")
         .append("</td></tr>")
         .append("<tr><td style=\"height:4px;background:").append(accent).append(";line-height:4px;font-size:0;\">&nbsp;</td></tr>")
         .append("<tr><td style=\"padding:24px;\">");
        if (headline != null)
            b.append("<h1 style=\"margin:0 0 16px;font-size:20px;line-height:1.3;color:#1c2430;\">").append(esc(headline)).append("</h1>");
        if (greeting != null) b.append(p(esc(greeting)));
        for (Block block : blocks) {
            switch (block) {
                case Para x -> b.append(p(linkify(esc(x.text()))));
                case Bullets l -> {
                    b.append("<ul style=\"margin:0 0 16px;padding-left:20px;font-size:14.5px;line-height:1.6;\">");
                    l.items().forEach(i -> b.append("<li>").append(esc(i)).append("</li>"));
                    b.append("</ul>");
                }
                case Details d -> {
                    b.append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"margin:0 0 16px;border:1px solid #e3e6eb;border-radius:8px;border-collapse:separate;font-size:14px;\">");
                    for (int i = 0; i < d.rows().size(); i++) {
                        String[] r = d.rows().get(i);
                        String line = i == 0 ? "" : "border-top:1px solid #eceef2;";
                        b.append("<tr><td style=\"padding:9px 12px;color:#6b7686;width:40%;").append(line).append("\">").append(esc(r[0]))
                         .append("</td><td style=\"padding:9px 12px;font-weight:600;").append(line).append("\">").append(linkify(esc(r[1]))).append("</td></tr>");
                    }
                    b.append("</table>");
                }
                case Box x -> b.append("<div style=\"margin:0 0 16px;padding:12px 14px;border-left:4px solid ").append(color(x.tone()))
                        .append(";background:").append(soft(x.tone())).append(";border-radius:6px;font-size:14px;line-height:1.55;\">")
                        .append("<div style=\"font-weight:700;margin-bottom:4px;\">").append(esc(x.title())).append("</div>")
                        .append(linkify(esc(x.text())).replace("\n", "<br>")).append("</div>");
            }
        }
        if (actionUrl != null)
            b.append("<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" style=\"margin:8px 0 20px;\"><tr><td style=\"border-radius:8px;background:")
             .append(accent).append(";\"><a href=\"").append(attr(actionUrl)).append("\" style=\"display:inline-block;padding:11px 20px;font-size:14.5px;font-weight:600;color:#ffffff;text-decoration:none;border-radius:8px;\">")
             .append(esc(actionLabel)).append("</a></td></tr></table>")
             .append("<p style=\"margin:0 0 16px;font-size:12.5px;color:#6b7686;\">Or open this link: <a href=\"").append(attr(actionUrl))
             .append("\" style=\"color:#1f6feb;word-break:break-all;\">").append(esc(actionUrl)).append("</a></p>");
        if (footnote != null)
            b.append("<p style=\"margin:0 0 4px;font-size:13px;color:#6b7686;line-height:1.5;\">").append(linkify(esc(footnote))).append("</p>");
        b.append("</td></tr>")
         .append("<tr><td style=\"padding:14px 24px;background:#f3f5f8;border-top:1px solid #e3e6eb;font-size:12px;color:#6b7686;line-height:1.5;\">")
         .append("You get this email because you have an account on ").append(BRAND).append(". ")
         .append("Backtest results are estimates; past results don’t guarantee future returns. Not investment advice.")
         .append("</td></tr></table>")
         .append("<p style=\"font-size:11.5px;color:#9aa3af;margin:12px 0 0;\">© ").append(java.time.Year.now().getValue())
         .append(" ").append(BRAND).append("</p>")
         .append("</td></tr></table></body></html>");
        return b.toString();
    }

    private static String p(String html) {
        return "<p style=\"margin:0 0 16px;font-size:14.5px;line-height:1.6;\">" + html + "</p>";
    }

    static String esc(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            switch (c) {
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '&' -> b.append("&amp;");
                case '"' -> b.append("&quot;");
                case '\'' -> b.append("&#39;");
                default -> b.append(c);
            }
        }
        return b.toString();
    }

    private static String attr(String url) { return esc(url); }

    /** Turns http(s) links in already-escaped text into anchors. */
    private static String linkify(String escaped) {
        return escaped.replaceAll("(https?://[^\\s<]+[^\\s<.,;:!?)])",
                "<a href=\"$1\" style=\"color:#1f6feb;\">$1</a>");
    }
}
