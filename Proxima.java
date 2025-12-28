import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

// NanoHTTPD adapter (optional). Add dependency and uncomment imports if you use it.
// import fi.iki.elonen.NanoHTTPD;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * No-JS / no-CSS demo UI served by OpenJDK HttpServer (and optionally NanoHTTPD).
 *
 * UI rules:
 * - Two-row table (2x1), no titles, no columns in main layout.
 * - Row 1: language select (<None> default) + Apply, and Add language (input + button).
 * - Row 2: messages in order:
 *   - links list: title + all translations; selected lang translation is bold
 *   - image
 *   - text: if selected <None> -> native text only;
 *           else -> 2-col table with native left and translation right;
 *                if translation missing -> textarea + Save
 *
 * No JS, no CSS. Page updates via Post/Redirect/Get with 303 and anchors (#m{id}) to preserve scroll.
 */
public class DemoNoJsHttpApp {

    // ===================== 1) Models =====================

    public static final class Link {
        public final int id;                         // message_id in sqlite
        public final String title;                   // native title
        public final Map<String, String> translate;  // lang -> title (optional, "on the side")

        public Link(int id, String title, Map<String, String> translate) {
            this.id = id;
            this.title = title;
            this.translate = (translate == null) ? Map.of() : Map.copyOf(translate);
        }
    }

    public static final class Message {
        public final int id;
        public final Integer image_id;               // nullable
        public final String text;                    // native text, nullable
        public final List<Link> links;
        public final Map<String, String> translate;  // lang -> text (optional, "on the side")

        public Message(int id, Integer imageId, String text, List<Link> links, Map<String, String> translate) {
            this.id = id;
            this.image_id = imageId;
            this.text = text;
            this.links = (links == null) ? List.of() : List.copyOf(links);
            this.translate = (translate == null) ? Map.of() : Map.copyOf(translate);
        }
    }

    // ===================== 2) Provider =====================

    public interface DemoProvider {
        List<Message> listMessages();
        byte[] getImageBytes(int imageId);

        /**
         * @return null on success; non-null = error message to show with an "Ok" button.
         */
        String addTranslation(int messageId, String lang, String translation);
    }

    /**
     * Demo in-memory provider: keeps extra translations in memory.
     */
    public static final class InMemoryDemoProvider implements DemoProvider {
        private final Map<Integer, Map<String, String>> extraTranslations = new ConcurrentHashMap<>();
        private final List<Message> base;

        public InMemoryDemoProvider() {
            base = List.of(
                new Message(
                    1, null,
                    "Привет. Это первое сообщение.\nИ оно в две строки.",
                    List.of(
                        new Link(3, "К ветке 3", Map.of("en", "To branch 3", "el", "Στον κλάδο 3")),
                        new Link(2, "К ветке 2", Map.of("en", "To branch 2"))
                    ),
                    Map.of("en", "Hi. This is the first message.\nIt has two lines.")
                ),
                new Message(
                    2, 1,
                    "Сообщение №2 с картинкой 1x1.",
                    List.of(new Link(1, "К ветке 1", Map.of("en", "To branch 1"))),
                    Map.of()
                ),
                new Message(
                    3, null,
                    "Третье сообщение без переводов.",
                    List.of(),
                    Map.of()
                )
            );
        }

        @Override
        public List<Message> listMessages() {
            List<Message> out = new ArrayList<>(base.size());
            for (Message m : base) {
                Map<String, String> merged = new HashMap<>(m.translate);
                merged.putAll(extraTranslations.getOrDefault(m.id, Map.of()));
                out.add(new Message(m.id, m.image_id, m.text, m.links, merged));
            }
            return out;
        }

        @Override
        public byte[] getImageBytes(int imageId) {
            if (imageId != 1) return null;
            // Valid 1x1 transparent PNG
            String b64 =
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR4nGNgYAAAAAMAASsJTYQAAAAASUVORK5CYII=";
            return Base64.getDecoder().decode(b64);
        }

        @Override
        public String addTranslation(int messageId, String lang, String translation) {
            if (messageId <= 0) return "Bad messageId";
            if (lang == null || lang.trim().isEmpty()) return "Language is empty";
            lang = lang.trim();
            if ("<None>".equals(lang)) return "Cannot save translation for <None>";
            if (translation == null) translation = "";
            if (translation.length() > 200_000) return "Translation too long";

            extraTranslations.computeIfAbsent(messageId, k -> new ConcurrentHashMap<>()).put(lang, translation);
            return null;
        }
    }

    // ===================== 3) Transport-agnostic HTTP types =====================

    public static final class HttpReq {
        public final String method;
        public final String path;
        public final Map<String, String> query;
        public final Map<String, String> headers; // lower-cased
        public final byte[] body;
        public final Session session;

        public HttpReq(String method, String path, Map<String, String> query, Map<String, String> headers, byte[] body, Session session) {
            this.method = method;
            this.path = path;
            this.query = query;
            this.headers = headers;
            this.body = body;
            this.session = session;
        }
    }

    public static final class HttpResp {
        public final int status;
        public final Map<String, String> headers;
        public final byte[] body;

        public HttpResp(int status, Map<String, String> headers, byte[] body) {
            this.status = status;
            this.headers = (headers == null) ? Map.of() : Map.copyOf(headers);
            this.body = (body == null) ? new byte[0] : body;
        }

        public static HttpResp html(int status, String html) {
            return new HttpResp(status, Map.of("Content-Type", "text/html; charset=utf-8"),
                html.getBytes(StandardCharsets.UTF_8));
        }

        public static HttpResp text(int status, String text) {
            return new HttpResp(status, Map.of("Content-Type", "text/plain; charset=utf-8"),
                text.getBytes(StandardCharsets.UTF_8));
        }

        public static HttpResp bytes(int status, String contentType, byte[] data) {
            return new HttpResp(status, Map.of("Content-Type", contentType), data);
        }

        public static HttpResp redirect303(String location) {
            return new HttpResp(303, Map.of("Location", location), new byte[0]);
        }
    }

    // ===================== 4) Session =====================

    public static final class Session {
        public final String id;
        public String selectedLang = "<None>";
        public final Set<String> extraLangs = new LinkedHashSet<>();
        public String lastError = null;
        public String lastErrorReturnTo = null; // e.g. "m2"

        public Session(String id) { this.id = id; }
    }

    public static final class SessionStore {
        private final Map<String, Session> sessions = new ConcurrentHashMap<>();
        private final SecureRandom rnd = new SecureRandom();

        public Session getOrCreate(String cookieSid) {
            if (cookieSid != null && !cookieSid.isBlank()) {
                Session s = sessions.get(cookieSid);
                if (s != null) return s;
            }
            String sid = newSid();
            Session s = new Session(sid);
            sessions.put(sid, s);
            return s;
        }

        private String newSid() {
            byte[] b = new byte[18];
            rnd.nextBytes(b);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        }
    }

    // ===================== 5) Router (NO JS / NO CSS) =====================

    public static final class Router {
        private final DemoProvider provider;

        public Router(DemoProvider provider) { this.provider = provider; }

        public HttpResp handle(HttpReq req) {
            try {
                if ("GET".equals(req.method) && ("/".equals(req.path) || "".equals(req.path))) {
                    return renderPage(req.session);
                }
                if ("GET".equals(req.method) && "/image".equals(req.path)) {
                    return handleImage(req);
                }

                if ("POST".equals(req.method) && "/api/setLang".equals(req.path)) return apiSetLang(req);
                if ("POST".equals(req.method) && "/api/addLang".equals(req.path)) return apiAddLang(req);
                if ("POST".equals(req.method) && "/api/saveTranslation".equals(req.path)) return apiSaveTranslation(req);
                if ("POST".equals(req.method) && "/api/clearError".equals(req.path)) return apiClearError(req);

                if ("GET".equals(req.method) && "/api/ping".equals(req.path)) {
                    return HttpResp.text(200, "ok " + Instant.now());
                }

                return HttpResp.text(404, "Not found");
            } catch (Exception e) {
                return HttpResp.text(500, "Server error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }

        private HttpResp handleImage(HttpReq req) {
            int id = parseInt(req.query.get("id"), -1);
            if (id <= 0) return HttpResp.text(400, "Bad image id");
            byte[] data = provider.getImageBytes(id);
            if (data == null) return HttpResp.text(404, "No image");
            return HttpResp.bytes(200, sniffContentType(data), data);
        }

        private HttpResp apiSetLang(HttpReq req) {
            Map<String, String> form = parseFormUrlEncoded(req.body);
            String lang = normalizeLang(form.get("lang"));
            String returnTo = normalizeReturnTo(form.get("returnTo"));

            req.session.selectedLang = lang;
            return redirectTo(returnTo);
        }

        private HttpResp apiAddLang(HttpReq req) {
            Map<String, String> form = parseFormUrlEncoded(req.body);
            String lang = (form.get("lang") == null) ? "" : form.get("lang").trim();
            String returnTo = normalizeReturnTo(form.get("returnTo"));

            if (lang.isEmpty() || "<None>".equals(lang)) {
                req.session.lastError = "Bad language";
                req.session.lastErrorReturnTo = returnTo;
                return redirectTo(returnTo);
            }

            req.session.extraLangs.add(lang);
            req.session.selectedLang = lang;
            return redirectTo(returnTo);
        }

        private HttpResp apiSaveTranslation(HttpReq req) {
            Map<String, String> form = parseFormUrlEncoded(req.body);
            int messageId = parseInt(form.get("messageId"), -1);
            String lang = normalizeLang(form.get("lang"));
            String translation = form.getOrDefault("translation", "");
            String returnTo = normalizeReturnTo(form.get("returnTo"));
            if (returnTo == null) returnTo = "m" + messageId;

            String err = provider.addTranslation(messageId, lang, translation);
            if (err == null) {
                req.session.lastError = null;
                req.session.lastErrorReturnTo = null;
            } else {
                req.session.lastError = err;
                req.session.lastErrorReturnTo = returnTo;
            }
            return redirectTo(returnTo);
        }

        private HttpResp apiClearError(HttpReq req) {
            Map<String, String> form = parseFormUrlEncoded(req.body);
            String returnTo = normalizeReturnTo(form.get("returnTo"));
            if (returnTo == null) returnTo = req.session.lastErrorReturnTo;

            req.session.lastError = null;
            req.session.lastErrorReturnTo = null;
            return redirectTo(returnTo);
        }

        private HttpResp redirectTo(String returnTo) {
            if (returnTo == null || returnTo.isBlank()) return HttpResp.redirect303("/");
            return HttpResp.redirect303("/#" + returnTo);
        }

        private HttpResp renderPage(Session session) {
            List<Message> messages = provider.listMessages();
            String selected = normalizeLang(session.selectedLang);

            // Collect languages from messages + links + extraLangs
            Set<String> langs = new LinkedHashSet<>();
            for (Message m : messages) {
                langs.addAll(m.translate.keySet());
                for (Link l : m.links) langs.addAll(l.translate.keySet());
            }
            langs.addAll(session.extraLangs);
            langs.remove("<None>");

            StringBuilder h = new StringBuilder(80_000);
            h.append("<!doctype html><html><head><meta charset='utf-8'>")
             .append("<meta name='viewport' content='width=device-width, initial-scale=1'>")
             .append("<title>Demo</title>")
             .append("</head><body>");

            // Two-row table (2x1): no titles, no columns in the main layout
            h.append("<table width='100%'>");

            // Row 1: controls in a single row WITHOUT CSS (using a nested table)
            h.append("<tr><td>");
            h.append("<table><tr><td>");

            // Set language (select + Apply)
            h.append("<form method='post' action='/api/setLang'>")
             .append("<select name='lang'>")
             .append(option("<None>", selected));

            List<String> sorted = new ArrayList<>(langs);
            Collections.sort(sorted);
            for (String l : sorted) h.append(option(l, selected));

            h.append("</select>")
             .append("<input type='hidden' name='returnTo' value=''>")
             .append("<button type='submit'>Apply</button>")
             .append("</form>");

            h.append("</td><td>");

            // Add language (input + button)
            h.append("<form method='post' action='/api/addLang'>")
             .append("<input name='lang' placeholder='lang'>")
             .append("<input type='hidden' name='returnTo' value=''>")
             .append("<button type='submit'>Add language</button>")
             .append("</form>");

            h.append("</td></tr></table>");
            h.append("</td></tr>");

            // Row 2: messages
            h.append("<tr><td>");

            // If we have an error without a target, show at top of messages
            if (session.lastError != null && !session.lastError.isBlank() &&
                (session.lastErrorReturnTo == null || session.lastErrorReturnTo.isBlank())) {
                renderErrorBlock(h, session.lastError, "");
            }

            for (Message m : messages) {
                // If error targets this message, show it right above the message so anchor scroll lands on it
                String mid = "m" + m.id;
                if (session.lastError != null && !session.lastError.isBlank() &&
                    mid.equals(session.lastErrorReturnTo)) {
                    renderErrorBlock(h, session.lastError, mid);
                }

                renderMessage(h, m, selected);
                h.append("<hr>");
            }

            h.append("</td></tr></table>");
            h.append("</body></html>");

            return HttpResp.html(200, h.toString());
        }

        private void renderErrorBlock(StringBuilder out, String error, String returnTo) {
            out.append("<div>");
            out.append("<b>Error:</b> ").append(escapeHtml(error));
            out.append("<form method='post' action='/api/clearError'>");
            out.append("<input type='hidden' name='returnTo' value='").append(escapeHtmlAttr(returnTo)).append("'>");
            out.append("<button type='submit'>Ok</button>");
            out.append("</form>");
            out.append("</div><hr>");
        }

        private void renderMessage(StringBuilder out, Message m, String selectedLang) {
            out.append("<div id='m").append(m.id).append("'>");

            // 2.2.1 links list: title then all translations; selected translation bold
            if (m.links != null && !m.links.isEmpty()) {
                for (Link l : m.links) {
                    out.append("<div>");

                    out.append("<a href='#m").append(l.id).append("'>")
                       .append(escapeHtml(nullToEmpty(l.title)))
                       .append("</a>");

                    if (l.translate != null && !l.translate.isEmpty()) {
                        List<String> keys = new ArrayList<>(l.translate.keySet());
                        Collections.sort(keys);
                        for (String k : keys) {
                            String v = l.translate.get(k);
                            if (v == null) continue;

                            boolean isSel = !"<None>".equals(selectedLang) && k.equals(selectedLang);
                            out.append(" ")
                               .append(escapeHtml(k))
                               .append(": ");
                            if (isSel) out.append("<b>");
                            out.append(escapeHtml(v));
                            if (isSel) out.append("</b>");
                        }
                    }
                    out.append("</div>");
                }
            }

            // 2.2.2 image
            if (m.image_id != null) {
                out.append("<div><img alt='' src='/image?id=")
                   .append(m.image_id)
                   .append("'></div>");
            }

            // 2.2.3 text
            if (m.text != null && !m.text.isBlank()) {
                if ("<None>".equals(selectedLang)) {
                    out.append("<pre>").append(escapeHtml(m.text)).append("</pre>");
                } else {
                    out.append("<table width='100%'><tr>");

                    // left: native
                    out.append("<td width='50%'><pre>")
                       .append(escapeHtml(m.text))
                       .append("</pre></td>");

                    // right: translation or editor
                    out.append("<td width='50%'>");
                    String tr = (m.translate == null) ? null : m.translate.get(selectedLang);
                    if (tr != null) {
                        out.append("<pre>").append(escapeHtml(tr)).append("</pre>");
                    } else {
                        out.append("<form method='post' action='/api/saveTranslation'>")
                           .append("<input type='hidden' name='messageId' value='").append(m.id).append("'>")
                           .append("<input type='hidden' name='lang' value='").append(escapeHtmlAttr(selectedLang)).append("'>")
                           .append("<input type='hidden' name='returnTo' value='m").append(m.id).append("'>")
                           .append("<textarea name='translation' rows='6' cols='40'></textarea><br>")
                           .append("<button type='submit'>Save</button>")
                           .append("</form>");
                    }
                    out.append("</td>");

                    out.append("</tr></table>");
                }
            }

            out.append("</div>");
        }

        // ================= helpers =================

        private static String normalizeLang(String lang) {
            if (lang == null || lang.isBlank()) return "<None>";
            return lang.trim();
        }

        private static String normalizeReturnTo(String rt) {
            if (rt == null) return null;
            rt = rt.trim();
            if (rt.isEmpty()) return null;
            if (rt.length() > 32) return null;
            for (int i = 0; i < rt.length(); i++) {
                char c = rt.charAt(i);
                if (!(Character.isLetterOrDigit(c) || c == '_' || c == '-')) return null;
            }
            return rt;
        }

        private static String option(String value, String selected) {
            return "<option value='" + escapeHtmlAttr(value) + "'" +
                (value.equals(selected) ? " selected" : "") +
                ">" + escapeHtml(value) + "</option>";
        }

        private static Map<String, String> parseFormUrlEncoded(byte[] bodyBytes) {
            if (bodyBytes == null || bodyBytes.length == 0) return Map.of();
            String body = new String(bodyBytes, StandardCharsets.UTF_8);
            if (body.isBlank()) return Map.of();

            Map<String, String> out = new LinkedHashMap<>();
            String[] parts = body.split("&");
            for (String p : parts) {
                if (p.isEmpty()) continue;
                int i = p.indexOf('=');
                String k = (i >= 0) ? p.substring(0, i) : p;
                String v = (i >= 0) ? p.substring(i + 1) : "";
                out.put(urlDecode(k), urlDecode(v));
            }
            return out;
        }

        private static String urlDecode(String s) {
            try {
                return URLDecoder.decode(s, StandardCharsets.UTF_8);
            } catch (Exception e) {
                return s;
            }
        }

        private static int parseInt(String s, int def) {
            try { return Integer.parseInt(s); } catch (Exception e) { return def; }
        }

        private static String nullToEmpty(String s) { return (s == null) ? "" : s; }

        private static String sniffContentType(byte[] data) {
            if (data.length >= 8 &&
                (data[0] & 0xFF) == 0x89 &&
                (data[1] & 0xFF) == 0x50 &&
                (data[2] & 0xFF) == 0x4E &&
                (data[3] & 0xFF) == 0x47) return "image/png";
            if (data.length >= 2 &&
                (data[0] & 0xFF) == 0xFF &&
                (data[1] & 0xFF) == 0xD8) return "image/jpeg";
            return "application/octet-stream";
        }

        private static String escapeHtml(String s) {
            if (s == null) return "";
            return s.replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;")
                    .replace("\"", "&quot;");
        }

        private static String escapeHtmlAttr(String s) {
            return escapeHtml(s).replace("'", "&#39;");
        }
    }

    // ===================== 6) OpenJDK HttpServer adapter =====================

    public static final class OpenJdkHttpServerAdapter {
        private final SessionStore sessions = new SessionStore();
        private final Router router;

        public OpenJdkHttpServerAdapter(Router router) { this.router = router; }

        public void start(String host, int port) throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 0);
            server.createContext("/", new Handler());
            server.setExecutor(null);
            server.start();
            System.out.println("Listening on http://" + host + ":" + port + "/");
        }

        private final class Handler implements HttpHandler {
            @Override
            public void handle(HttpExchange ex) throws IOException {
                URI uri = ex.getRequestURI();
                String path = (uri.getPath() == null || uri.getPath().isBlank()) ? "/" : uri.getPath();

                Map<String, String> query = parseQuery(uri.getRawQuery());
                Map<String, String> headers = lowerHeaders(ex.getRequestHeaders());

                Session session = sessions.getOrCreate(readSidCookie(headers.get("cookie")));
                byte[] body = readAllBytes(ex.getRequestBody());

                HttpReq req = new HttpReq(
                    ex.getRequestMethod(),
                    path,
                    query,
                    headers,
                    body,
                    session
                );

                HttpResp resp = router.handle(req);

                Headers rh = ex.getResponseHeaders();
                rh.add("Set-Cookie", "SID=" + session.id + "; Path=/; HttpOnly; SameSite=Lax");
                for (var e : resp.headers.entrySet()) rh.add(e.getKey(), e.getValue());

                ex.sendResponseHeaders(resp.status, resp.body.length);
                try (OutputStream os = ex.getResponseBody()) { os.write(resp.body); }
            }
        }

        private static Map<String, String> lowerHeaders(Headers h) {
            Map<String, String> out = new HashMap<>();
            for (var e : h.entrySet()) {
                if (e.getKey() == null) continue;
                String k = e.getKey().toLowerCase(Locale.ROOT);
                String v = (e.getValue() == null || e.getValue().isEmpty()) ? "" : String.join(", ", e.getValue());
                out.put(k, v);
            }
            return out;
        }

        private static Map<String, String> parseQuery(String raw) {
            if (raw == null || raw.isBlank()) return Map.of();
            Map<String, String> out = new LinkedHashMap<>();
            String[] parts = raw.split("&");
            for (String p : parts) {
                if (p.isEmpty()) continue;
                int i = p.indexOf('=');
                String k = (i >= 0) ? p.substring(0, i) : p;
                String v = (i >= 0) ? p.substring(i + 1) : "";
                out.put(urlDecode(k), urlDecode(v));
            }
            return out;
        }

        private static String urlDecode(String s) {
            try { return URLDecoder.decode(s, StandardCharsets.UTF_8); }
            catch (Exception e) { return s; }
        }

        private static String readSidCookie(String cookieHeader) {
            if (cookieHeader == null) return null;
            String[] parts = cookieHeader.split(";");
            for (String p : parts) {
                String s = p.trim();
                if (s.startsWith("SID=")) return s.substring("SID=".length()).trim();
            }
            return null;
        }

        private static byte[] readAllBytes(InputStream is) throws IOException {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            while ((r = is.read(buf)) >= 0) bos.write(buf, 0, r);
            return bos.toByteArray();
        }
    }

    // ===================== 7) NanoHTTPD adapter (short) =====================
    //
    // To use it:
    // - Add NanoHTTPD dependency to your build (Maven/Gradle).
    // - Uncomment the NanoHTTPD import at top.
    // - Uncomment the class below.
    // - Start it instead of OpenJDK adapter in main().
    //
    // Notes:
    // - We read body via session.parseBody(files) and take files.get("postData") for form-url-encoded posts.
    // - Session cookie is maintained the same way (SID cookie).
    //

    /*
    public static final class NanoHttpdAdapter extends NanoHTTPD {
        private final SessionStore sessions = new SessionStore();
        private final Router router;

        public NanoHttpdAdapter(String host, int port, Router router) {
            super(host, port);
            this.router = router;
        }

        @Override
        public Response serve(IHTTPSession s) {
            try {
                String method = s.getMethod().name();
                String path = s.getUri();
                Map<String, String> headers = lowerHeadersNano(s.getHeaders());

                Session session = sessions.getOrCreate(readSidCookie(headers.get("cookie")));

                Map<String, String> query = parseQueryNano(s.getQueryParameterString());
                byte[] body = readBodyNano(s);

                HttpReq req = new HttpReq(method, path, query, headers, body, session);
                HttpResp resp = router.handle(req);

                String contentType = resp.headers.getOrDefault("Content-Type", "application/octet-stream");
                Response.Status st = Response.Status.lookup(resp.status);
                if (st == null) st = Response.Status.OK;

                Response r = NanoHTTPD.newFixedLengthResponse(
                    st,
                    contentType,
                    new ByteArrayInputStream(resp.body),
                    resp.body.length
                );

                // Set cookie
                r.addHeader("Set-Cookie", "SID=" + session.id + "; Path=/; HttpOnly; SameSite=Lax");

                // Other headers (avoid duplicating Content-Type)
                for (var e : resp.headers.entrySet()) {
                    String k = e.getKey();
                    if (k == null) continue;
                    if ("Content-Type".equalsIgnoreCase(k)) continue;
                    r.addHeader(k, e.getValue());
                }

                return r;
            } catch (Exception e) {
                return NanoHTTPD.newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR,
                    "text/plain; charset=utf-8",
                    "Server error: " + e.getClass().getSimpleName() + ": " + e.getMessage()
                );
            }
        }

        private static Map<String, String> lowerHeadersNano(Map<String, String> h) {
            Map<String, String> out = new HashMap<>();
            for (var e : h.entrySet()) {
                if (e.getKey() == null) continue;
                out.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue() == null ? "" : e.getValue());
            }
            return out;
        }

        private static byte[] readBodyNano(IHTTPSession s) {
            try {
                if (!Method.POST.equals(s.getMethod()) && !Method.PUT.equals(s.getMethod()) && !Method.PATCH.equals(s.getMethod())) {
                    return new byte[0];
                }
                Map<String, String> files = new HashMap<>();
                s.parseBody(files);
                String postData = files.get("postData"); // for x-www-form-urlencoded typical case
                if (postData == null) return new byte[0];
                return postData.getBytes(StandardCharsets.UTF_8);
            } catch (Exception ignored) {
                return new byte[0];
            }
        }

        private static Map<String, String> parseQueryNano(String raw) {
            if (raw == null || raw.isBlank()) return Map.of();
            Map<String, String> out = new LinkedHashMap<>();
            String[] parts = raw.split("&");
            for (String p : parts) {
                if (p.isEmpty()) continue;
                int i = p.indexOf('=');
                String k = (i >= 0) ? p.substring(0, i) : p;
                String v = (i >= 0) ? p.substring(i + 1) : "";
                out.put(urlDecodeNano(k), urlDecodeNano(v));
            }
            return out;
        }

        private static String urlDecodeNano(String s) {
            try { return URLDecoder.decode(s, StandardCharsets.UTF_8); }
            catch (Exception e) { return s; }
        }

        private static String readSidCookie(String cookieHeader) {
            if (cookieHeader == null) return null;
            String[] parts = cookieHeader.split(";");
            for (String p : parts) {
                String x = p.trim();
                if (x.startsWith("SID=")) return x.substring("SID=".length()).trim();
            }
            return null;
        }
    }
    */

    // ===================== main =====================

    public static void main(String[] args) throws Exception {
        DemoProvider provider = new InMemoryDemoProvider();
        Router router = new Router(provider);

        // OpenJDK HttpServer (desktop, Oracle Cloud, etc.)
        new OpenJdkHttpServerAdapter(router).start("127.0.0.1", 8080);

        // NanoHTTPD (Android-friendly)
        // NanoHttpdAdapter nano = new NanoHttpdAdapter("127.0.0.1", 8080, router);
        // nano.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
        // System.out.println("Listening on http://127.0.0.1:8080/");
    }
}
