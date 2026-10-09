const state = { menu: [], cart: {}, orderId: localStorage.getItem("canteenOrderId"), timer: null };
const STEPS = ["PLACED", "PREPARING", "READY", "COLLECTED"];
const STEP_LABEL = { PLACED: "Received", PREPARING: "Cooking", READY: "Ready", COLLECTED: "Collected" };

const esc = s => String(s ?? "").replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));

async function api(path, options) {
  const res = await fetch(path, { headers: { "Content-Type": "application/json" }, ...options });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(data.error || "Request failed");
  return data;
}

/* ---------- menu ---------- */
async function loadMenu() {
  state.menu = await api("/api/menu");
  renderMenu();
}

function renderMenu() {
  const groups = {};
  state.menu.forEach(m => (groups[m.category] ||= []).push(m));
  let html = "";
  for (const [cat, items] of Object.entries(groups)) {
    html += `<h2>${esc(cat)}</h2>`;
    for (const m of items) {
      const q = state.cart[m.id] || 0;
      const right = m.available
        ? `<span class="qty">
             <button data-act="dec" data-id="${m.id}" aria-label="Remove one ${esc(m.name)}" ${q === 0 ? "disabled" : ""}>−</button>
             <span>${q}</span>
             <button data-act="inc" data-id="${m.id}" aria-label="Add one ${esc(m.name)}">+</button>
           </span>`
        : `<span class="soldtag">Sold out</span>`;
      html += `<div class="dish ${m.available ? "" : "sold"}">
        <div class="info"><div class="name">${esc(m.name)}</div><div class="desc">${esc(m.description)}</div></div>
        <div class="leader"></div>
        <div class="price">₹${m.price}</div>
        ${right}
      </div>`;
    }
  }
  document.getElementById("menu").innerHTML = html || "<p>The menu is empty right now.</p>";
}

document.getElementById("menu").addEventListener("click", e => {
  const b = e.target.closest("button[data-act]");
  if (!b) return;
  const id = b.dataset.id;
  const q = state.cart[id] || 0;
  if (b.dataset.act === "inc" && q < 20) state.cart[id] = q + 1;
  if (b.dataset.act === "dec" && q > 0) state.cart[id] = q - 1;
  if (state.cart[id] === 0) delete state.cart[id];
  renderMenu();
  if (!state.orderId) renderCart();
});

/* ---------- cart / slip ---------- */
function renderCart() {
  const slip = document.getElementById("slip");
  const lines = Object.entries(state.cart).map(([id, qty]) => {
    const m = state.menu.find(x => String(x.id) === id);
    return m && { m, qty };
  }).filter(Boolean);
  const total = lines.reduce((s, l) => s + l.m.price * l.qty, 0);

  const saved = JSON.parse(localStorage.getItem("canteenCustomer") || "{}");
  slip.innerHTML = `
    <h2>Your order</h2>
    ${lines.length === 0
      ? `<p class="empty">Add items from the menu to start your order.</p>`
      : `<ul>${lines.map(l => `<li><span>${l.qty} × ${esc(l.m.name)}</span><span>₹${l.m.price * l.qty}</span></li>`).join("")}</ul>
         <div class="total"><span>Total</span><span>₹${total}</span></div>
         <label>Name<input id="cname" value="${esc(saved.name)}" autocomplete="name"></label>
         <label>Roll number<input id="croll" value="${esc(saved.roll)}"></label>
         <button class="btn" id="place">Place order</button>
         <p class="muted" style="font-size:.85rem;color:#5b6b66">Pay at the counter when you collect.</p>`}
    <div class="msg" id="msg"></div>`;

  const place = document.getElementById("place");
  if (place) place.onclick = placeOrder;
}

async function placeOrder() {
  const name = document.getElementById("cname").value;
  const roll = document.getElementById("croll").value;
  const msg = document.getElementById("msg");
  const btn = document.getElementById("place");
  btn.disabled = true;
  try {
    const order = await api("/api/orders", {
      method: "POST",
      body: JSON.stringify({
        customerName: name,
        rollNo: roll,
        items: Object.entries(state.cart).map(([id, qty]) => ({ itemId: Number(id), qty }))
      })
    });
    localStorage.setItem("canteenCustomer", JSON.stringify({ name, roll }));
    localStorage.setItem("canteenOrderId", order.id);
    state.orderId = order.id;
    state.cart = {};
    renderMenu();
    showOrder(order);
    startPolling();
  } catch (err) {
    msg.textContent = err.message;
    btn.disabled = false;
    loadMenu(); // item may have sold out
  }
}

/* ---------- tracking ---------- */
function showOrder(o) {
  const slip = document.getElementById("slip");
  const cancelled = o.status === "CANCELLED";
  const idx = STEPS.indexOf(o.status);
  const steps = cancelled ? "" : `<div class="steps">${STEPS.map((s, i) =>
    `<div class="${i < idx ? "done" : i === idx ? "on" : ""}">${STEP_LABEL[s]}</div>`).join("")}</div>`;
  const note = cancelled ? "This order was cancelled. Please speak to the counter staff."
    : o.status === "READY" ? "Your food is ready. Show this token at the counter."
    : o.status === "COLLECTED" ? "Enjoy your meal!"
    : "We will update this page when the status changes.";

  slip.innerHTML = `
    <div class="token"><div class="label">Your token</div><div class="num">${o.tokenNumber}</div></div>
    ${steps}
    <p>${note}</p>
    <ul>${o.lines.map(l => `<li><span>${l.qty} × ${esc(l.itemName)}</span><span>₹${l.price * l.qty}</span></li>`).join("")}</ul>
    <div class="total"><span>Pay at counter</span><span>₹${o.total}</span></div>
    ${(o.status === "COLLECTED" || cancelled) ? `<button class="btn alt" id="again">Start a new order</button>` : ""}`;
  const again = document.getElementById("again");
  if (again) again.onclick = () => {
    localStorage.removeItem("canteenOrderId");
    state.orderId = null;
    clearInterval(state.timer);
    renderCart();
  };
}

async function refreshOrder() {
  try {
    const o = await api("/api/orders/" + state.orderId);
    showOrder(o);
    if (o.status === "COLLECTED" || o.status === "CANCELLED") clearInterval(state.timer);
  } catch (e) {
    // order no longer exists (e.g. database reset)
    localStorage.removeItem("canteenOrderId");
    state.orderId = null;
    clearInterval(state.timer);
    renderCart();
  }
}

function startPolling() {
  clearInterval(state.timer);
  state.timer = setInterval(refreshOrder, 5000);
}

/* ---------- start ---------- */
(async function init() {
  await loadMenu();
  if (state.orderId) { await refreshOrder(); startPolling(); }
  else renderCart();
})();
