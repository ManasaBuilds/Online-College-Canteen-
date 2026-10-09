import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Campus Canteen - plain Java backend (no Spring, no Maven, no libraries).
 * Run this file, then open http://localhost:8080
 * Data is kept in memory, so the menu resets and orders are cleared when you stop the program.
 */
public class Main {

    static final int PORT = 8080;
    static final String ADMIN_KEY = "staff123";   // change this

    static final List<Map<String, Object>> menu = new ArrayList<>();
    static final List<Map<String, Object>> orders = new ArrayList<>();
    static long nextItemId = 1;
    static long nextOrderId = 1;
    static int tokenCounter = 0;
    static LocalDate tokenDay = LocalDate.now();

    // ------------------------------------------------------------------ start
    public static void main(String[] args) throws IOException {
        seedMenu();
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/", Main::handle);
        server.start();
        System.out.println("Canteen running at http://localhost:" + PORT);
        System.out.println("Staff page: http://localhost:" + PORT + "/admin.html  (key: " + ADMIN_KEY + ")");
    }

    static void seedMenu() {
        addItem("Masala Dosa", "Breakfast", "Crisp dosa, potato filling, sambar, chutney", 50);
        addItem("Idli Sambar (3 pcs)", "Breakfast", "Steamed idlis with sambar", 30);
        addItem("Poha", "Breakfast", "Light and fresh, with sev and lemon", 25);
        addItem("Veg Thali", "Lunch", "2 rotis, dal, sabzi, rice, salad", 80);
        addItem("Chole Bhature", "Lunch", "Two bhature with chole", 60);
        addItem("Veg Biryani", "Lunch", "Served with raita", 70);
        addItem("Samosa (2 pcs)", "Snacks", "With green chutney", 20);
        addItem("Vada Pav", "Snacks", "Mumbai classic", 15);
        addItem("Veg Sandwich", "Snacks", "Grilled, with ketchup", 35);
        addItem("Masala Chai", "Drinks", "Cutting chai", 10);
        addItem("Cold Coffee", "Drinks", "Thick and chilled", 40);
        addItem("Fresh Lime Soda", "Drinks", "Sweet or salted", 25);
    }

    static Map<String, Object> addItem(String name, String category, String description, int price) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", nextItemId++);
        m.put("name", name);
        m.put("category", category);
        m.put("description", description);
        m.put("price", price);
        m.put("available", true);
        menu.add(m);
        return m;
    }

    // ---------------------------------------------------------------- routing
    static class ApiError extends RuntimeException {
        final int status;
        ApiError(int status, String message) { super(message); this.status = status; }
    }

    static void handle(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            if (path.startsWith("/api/")) {
                String body = new String(readAll(ex.getRequestBody()), StandardCharsets.UTF_8);
                String key = ex.getRequestHeaders().getFirst("X-Admin-Key");
                Object result = route(ex.getRequestMethod(), path, body, key);
                sendJson(ex, 200, result);
            } else {
                serveStatic(ex, path);
            }
        } catch (ApiError e) {
            sendJson(ex, e.status, error(e.getMessage()));
        } catch (Exception e) {
            e.printStackTrace();
            sendJson(ex, 500, error("Something went wrong on the server"));
        }
    }

    static synchronized Object route(String method, String path, String body, String key) {
        String[] p = path.split("/");   // "", "api", "orders", "5"

        // ----- staff-only routes
        if (p.length > 2 && p[2].equals("admin")) {
            if (!ADMIN_KEY.equals(key)) throw new ApiError(401, "Wrong or missing staff key");

            if (method.equals("GET") && path.equals("/api/admin/orders")) return activeOrders();

            if (method.equals("PATCH") && p.length == 6 && p[3].equals("orders") && p[5].equals("status")) {
                Map<String, Object> order = findOrder(p[4]);
                String status = str(parseBody(body), "status");
                if (!Arrays.asList("PLACED", "PREPARING", "READY", "COLLECTED", "CANCELLED").contains(status))
                    throw new ApiError(400, "Unknown status");
                order.put("status", status);
                return order;
            }

            if (p.length >= 4 && p[3].equals("menu")) {
                if (method.equals("POST") && p.length == 4) {
                    Map<String, Object> b = parseBody(body);
                    validateItem(b);
                    return addItem(str(b, "name").trim(), str(b, "category").trim(),
                            str(b, "description"), num(b, "price"));
                }
                if (p.length == 5) {
                    Map<String, Object> item = findItem(p[4]);
                    if (method.equals("PUT")) {
                        Map<String, Object> b = parseBody(body);
                        validateItem(b);
                        item.put("name", str(b, "name").trim());
                        item.put("category", str(b, "category").trim());
                        item.put("description", str(b, "description"));
                        item.put("price", num(b, "price"));
                        item.put("available", Boolean.TRUE.equals(b.get("available")));
                        return item;
                    }
                    if (method.equals("DELETE")) {
                        menu.remove(item);
                        return new LinkedHashMap<String, Object>();
                    }
                }
            }
            throw new ApiError(404, "Not found");
        }

        // ----- public routes
        if (method.equals("GET") && path.equals("/api/menu")) return menu;
        if (method.equals("POST") && path.equals("/api/orders")) return placeOrder(parseBody(body));
        if (method.equals("GET") && p.length == 4 && p[2].equals("orders")) return findOrder(p[3]);

        throw new ApiError(404, "Not found");
    }

    // ----------------------------------------------------------- order logic
    static Map<String, Object> placeOrder(Map<String, Object> b) {
        String name = str(b, "customerName");
        String roll = str(b, "rollNo");
        if (name == null || name.isBlank()) throw new ApiError(400, "Enter your name");
        if (roll == null || roll.isBlank()) throw new ApiError(400, "Enter your roll number");
        if (!(b.get("items") instanceof List) || ((List<?>) b.get("items")).isEmpty())
            throw new ApiError(400, "Your cart is empty");

        List<Object> lines = new ArrayList<>();
        int total = 0;
        for (Object o : (List<?>) b.get("items")) {
            if (!(o instanceof Map)) throw new ApiError(400, "Bad item in cart");
            @SuppressWarnings("unchecked")
            Map<String, Object> line = (Map<String, Object>) o;
            int qty = num(line, "qty");
            if (qty < 1 || qty > 20) throw new ApiError(400, "Quantity must be between 1 and 20");
            Map<String, Object> item = findItemOrNull(String.valueOf(num(line, "itemId")));
            if (item == null) throw new ApiError(400, "An item in your cart no longer exists");
            if (!Boolean.TRUE.equals(item.get("available")))
                throw new ApiError(400, item.get("name") + " is sold out");

            int price = (Integer) item.get("price");     // price always comes from the server
            Map<String, Object> l = new LinkedHashMap<>();
            l.put("itemId", item.get("id"));
            l.put("itemName", item.get("name"));
            l.put("price", price);
            l.put("qty", qty);
            lines.add(l);
            total += price * qty;
        }

        if (!tokenDay.equals(LocalDate.now())) { tokenDay = LocalDate.now(); tokenCounter = 0; }

        Map<String, Object> order = new LinkedHashMap<>();
        order.put("id", nextOrderId++);
        order.put("tokenNumber", ++tokenCounter);
        order.put("customerName", name.trim());
        order.put("rollNo", roll.trim());
        order.put("total", total);
        order.put("status", "PLACED");
        order.put("createdAt", LocalDateTime.now().toString());
        order.put("lines", lines);
        orders.add(order);
        return order;
    }

    static List<Map<String, Object>> activeOrders() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> o : orders) {
            String s = (String) o.get("status");
            if (s.equals("PLACED") || s.equals("PREPARING") || s.equals("READY")) out.add(o);
        }
        return out;   // already oldest first
    }

    static void validateItem(Map<String, Object> b) {
        String name = str(b, "name");
        String cat = str(b, "category");
        if (name == null || name.isBlank()) throw new ApiError(400, "Item name is required");
        if (cat == null || cat.isBlank()) throw new ApiError(400, "Category is required");
        if (num(b, "price") <= 0) throw new ApiError(400, "Price must be more than 0");
    }

    static Map<String, Object> findItem(String id) {
        Map<String, Object> m = findItemOrNull(id);
        if (m == null) throw new ApiError(404, "Item not found");
        return m;
    }

    static Map<String, Object> findItemOrNull(String id) {
        for (Map<String, Object> m : menu) if (String.valueOf(m.get("id")).equals(id)) return m;
        return null;
    }

    static Map<String, Object> findOrder(String id) {
        for (Map<String, Object> o : orders) if (String.valueOf(o.get("id")).equals(id)) return o;
        throw new ApiError(404, "Order not found");
    }

    // ------------------------------------------------------------ small helpers
    @SuppressWarnings("unchecked")
    static Map<String, Object> parseBody(String body) {
        if (body == null || body.isBlank()) return new LinkedHashMap<>();
        try {
            Object o = new Json(body).parse();
            if (o instanceof Map) return (Map<String, Object>) o;
        } catch (RuntimeException e) { /* fall through */ }
        throw new ApiError(400, "Invalid request data");
    }

    static String str(Map<String, Object> m, String key) {
        Object o = m.get(key);
        return o == null ? null : o.toString();
    }

    static int num(Map<String, Object> m, String key) {
        Object o = m.get(key);
        if (o instanceof Number) return ((Number) o).intValue();
        try { return Integer.parseInt(String.valueOf(o).trim()); } catch (Exception e) { return 0; }
    }

    static Map<String, Object> error(String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", msg);
        return m;
    }

    static byte[] readAll(InputStream in) throws IOException { return in.readAllBytes(); }

    static void sendJson(HttpExchange ex, int status, Object body) throws IOException {
        send(ex, status, "application/json; charset=utf-8", Json.write(body).getBytes(StandardCharsets.UTF_8));
    }

    static void send(HttpExchange ex, int status, String type, byte[] bytes) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) { out.write(bytes); }
    }

    static void serveStatic(HttpExchange ex, String path) throws IOException {
        Path base = Paths.get("static").toAbsolutePath().normalize();
        if (path.equals("/")) path = "/index.html";
        Path file = base.resolve(path.substring(1)).normalize();
        if (!file.startsWith(base) || !Files.isRegularFile(file)) {
            send(ex, 404, "text/plain; charset=utf-8", "Page not found".getBytes(StandardCharsets.UTF_8));
            return;
        }
        String name = file.getFileName().toString();
        String type = name.endsWith(".html") ? "text/html; charset=utf-8"
                : name.endsWith(".css") ? "text/css; charset=utf-8"
                : name.endsWith(".js") ? "application/javascript; charset=utf-8"
                : "application/octet-stream";
        send(ex, 200, type, Files.readAllBytes(file));
    }

    // ------------------------------------------------------- tiny JSON reader/writer
    static class Json {
        private final String s;
        private int i = 0;

        Json(String s) { this.s = s; }

        Object parse() { return value(); }

        private void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }

        private Object value() {
            ws();
            char c = s.charAt(i);
            if (c == '{') return object();
            if (c == '[') return array();
            if (c == '"') return string();
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            return number();
        }

        private Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++;                                   // {
            ws();
            if (s.charAt(i) == '}') { i++; return m; }
            while (true) {
                ws();
                String k = string();
                ws();
                i++;                               // :
                m.put(k, value());
                ws();
                if (s.charAt(i++) == '}') return m;   // ',' means keep going
            }
        }

        private List<Object> array() {
            List<Object> l = new ArrayList<>();
            i++;                                   // [
            ws();
            if (s.charAt(i) == ']') { i++; return l; }
            while (true) {
                l.add(value());
                ws();
                if (s.charAt(i++) == ']') return l;
            }
        }

        private String string() {
            StringBuilder sb = new StringBuilder();
            i++;                                   // opening quote
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    char e = s.charAt(i++);
                    switch (e) {
                        case 'n': sb.append('\n'); break;
                        case 't': sb.append('\t'); break;
                        case 'r': sb.append('\r'); break;
                        case 'u': sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
                        default: sb.append(e);
                    }
                } else {
                    sb.append(c);
                }
            }
        }

        private Object number() {
            int start = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            String t = s.substring(start, i);
            if (t.isEmpty()) throw new IllegalArgumentException("bad json");
            if (t.contains(".") || t.contains("e") || t.contains("E")) return Double.valueOf(t);
            return Long.valueOf(t);
        }

        static String write(Object o) {
            StringBuilder sb = new StringBuilder();
            write(o, sb);
            return sb.toString();
        }

        private static void write(Object o, StringBuilder sb) {
            if (o == null) {
                sb.append("null");
            } else if (o instanceof Map) {
                sb.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) {
                    if (!first) sb.append(',');
                    first = false;
                    quote(String.valueOf(e.getKey()), sb);
                    sb.append(':');
                    write(e.getValue(), sb);
                }
                sb.append('}');
            } else if (o instanceof List) {
                sb.append('[');
                boolean first = true;
                for (Object x : (List<?>) o) {
                    if (!first) sb.append(',');
                    first = false;
                    write(x, sb);
                }
                sb.append(']');
            } else if (o instanceof Number || o instanceof Boolean) {
                sb.append(o);
            } else {
                quote(o.toString(), sb);
            }
        }

        private static void quote(String t, StringBuilder sb) {
            sb.append('"');
            for (char c : t.toCharArray()) {
                switch (c) {
                    case '"': sb.append("\\\""); break;
                    case '\\': sb.append("\\\\"); break;
                    case '\n': sb.append("\\n"); break;
                    case '\r': sb.append("\\r"); break;
                    case '\t': sb.append("\\t"); break;
                    default:
                        if (c < 0x20) sb.append(String.format("\\u%04x", (int) c)); else sb.append(c);
                }
            }
            sb.append('"');
        }
    }
}
