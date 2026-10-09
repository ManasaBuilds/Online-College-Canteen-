let key = sessionStorage.getItem("canteenKey") || "";
let timer = null;
let items = [];

const $ = id => document.getElementById(id);
const esc = s => String(s ?? "").replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));

async function api(path, options = {}) {
  const res = await fetch(path, {
    ...options,
    headers: { "Content-Type": "application/json", "X-Admin-Key": key }
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(data.error || "Request failed");
  return data;
}

/* ---------- login ---------- */
async function enter() {
  try {
    await api("/api/admin/orders");
    sessionStorage.setItem("canteenKey", key);
    $("gate").classList.add("hidden");
    $("app").classList.remove("hidden");
    loadOrders();
    timer = setInterval(loadOrders, 5000);
  } catch (e) {
    $("gatemsg").textContent = "That key didn't work. Check ADMIN_KEY in Main.java.";
  }
}
$("login").onclick = () => { key = $("key").value; enter(); };
$("key").addEventListener("keydown", e => { if (e.key === "Enter") $("login").click(); });
if (key) enter();

/* ---------- tabs ---------- */
$("tab-orders").onclick = () => showTab("orders");
$("tab-menu").onclick = () => showTab("menu");
function showTab(t) {
  $("orders").classList.toggle("hidden", t !== "orders");
  $("menusec").classList.toggle("hidden", t !== "menu");
  $("tab-orders").classList.toggle("on", t === "orders");
  $("tab-menu").classList.toggle("on", t === "menu");
  if (t === "menu") loadMenu();
}

/* ---------- orders ---------- */
const NEXT = { PLACED: ["PREPARING", "Start cooking"], PREPARING: ["READY", "Mark ready"], READY: ["COLLECTED", "Mark collected"] };

async function loadOrders() {
  try {
    const orders = await api("/api/admin/orders");
    $("cards").innerHTML = orders.length === 0
      ? `<p>No active orders. New orders appear here automatically.</p>`
      : orders.map(o => {
          const [next, label] = NEXT[o.status];
          return `<article class="ticket">
            <div class="head"><span class="tk">#${o.tokenNumber}</span><span class="pill ${o.status}">${o.status}</span></div>
            <div class="who">${esc(o.customerName)} · ${esc(o.rollNo)}</div>
            <ul>${o.lines.map(l => `<li>${l.qty} × ${esc(l.itemName)}</li>`).join("")}</ul>
            <strong>₹${o.total}</strong>
            <div class="actions" style="margin-top:10px">
              <button data-id="${o.id}" data-status="${next}">${label}</button>
              <button class="cancel" data-id="${o.id}" data-status="CANCELLED">Cancel</button>
            </div>
          </article>`;
        }).join("");
  } catch (e) { /* ignore a failed poll */ }
}

$("cards").addEventListener("click", async e => {
  const b = e.target.closest("button[data-status]");
  if (!b) return;
  if (b.dataset.status === "CANCELLED" && !confirm("Cancel this order?")) return;
  await api(`/api/admin/orders/${b.dataset.id}/status`, {
    method: "PATCH",
    body: JSON.stringify({ status: b.dataset.status })
  });
  loadOrders();
});

/* ---------- menu ---------- */
async function loadMenu() {
  items = await api("/api/menu");
  $("rows").innerHTML = items.map(m => `<tr>
    <td>${esc(m.name)}</td><td>${esc(m.category)}</td><td>₹${m.price}</td>
    <td>${m.available ? "Available" : "Sold out"}</td>
    <td>
      <button data-act="toggle" data-id="${m.id}">${m.available ? "Mark sold out" : "Mark available"}</button>
      <button class="del" data-act="del" data-id="${m.id}">Delete</button>
    </td></tr>`).join("");
}

$("rows").addEventListener("click", async e => {
  const b = e.target.closest("button[data-act]");
  if (!b) return;
  const item = items.find(i => String(i.id) === b.dataset.id);
  try {
    if (b.dataset.act === "toggle") {
      await api("/api/admin/menu/" + item.id, { method: "PUT", body: JSON.stringify({ ...item, available: !item.available }) });
    } else if (confirm(`Delete ${item.name}?`)) {
      await api("/api/admin/menu/" + item.id, { method: "DELETE" });
    }
    loadMenu();
  } catch (err) { $("menumsg").textContent = err.message; }
});

$("add").onclick = async () => {
  try {
    await api("/api/admin/menu", {
      method: "POST",
      body: JSON.stringify({
        name: $("n-name").value, category: $("n-cat").value,
        description: $("n-desc").value, price: Number($("n-price").value), available: true
      })
    });
    ["n-name", "n-cat", "n-desc", "n-price"].forEach(id => $(id).value = "");
    $("menumsg").textContent = "";
    loadMenu();
  } catch (err) { $("menumsg").textContent = err.message; }
};
