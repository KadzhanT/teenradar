// TeenRadar front end: decoder, paywall, subscribe. Everything user-provided is rendered with textContent.
const $ = (id) => document.getElementById(id);

const RISK_LABEL = {
  GREEN: '🟢 Harmless: normal teen talk',
  YELLOW: '🟡 Worth a calm check-in',
  RED: '🔴 Possible danger: talk today',
};

function el(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text != null) node.textContent = text;
  return node;
}

// ---------- Premium modal ----------
const modal = $('premium-modal');

function openPremium(reason) {
  $('paywall-reason').textContent = reason || '';
  modal.classList.remove('hidden');
}

document.querySelectorAll('[data-open-premium]').forEach((b) =>
  b.addEventListener('click', (e) => { e.preventDefault(); openPremium(); }));
document.querySelectorAll('[data-close-premium]').forEach((b) =>
  b.addEventListener('click', () => modal.classList.add('hidden')));
modal.addEventListener('click', (e) => { if (e.target === modal) modal.classList.add('hidden'); });
document.addEventListener('keydown', (e) => { if (e.key === 'Escape') modal.classList.add('hidden'); });
if (location.hash === '#premium') openPremium();

$('checkout-btn').addEventListener('click', async () => {
  const btn = $('checkout-btn');
  btn.disabled = true;
  btn.textContent = 'Opening secure checkout…';
  try {
    const res = await fetch('/api/checkout', { method: 'POST' });
    const data = await res.json();
    if (!res.ok) throw new Error(data.error || 'Checkout failed');
    location.href = data.url;
  } catch (err) {
    $('paywall-reason').textContent = err.message;
    btn.disabled = false;
    btn.textContent = 'Start Premium';
  }
});

// ---------- Decoder ----------
document.querySelectorAll('[data-sample]').forEach((b) =>
  b.addEventListener('click', () => { $('text').value = b.dataset.sample; $('text').focus(); }));

function showError(message) {
  $('error').textContent = message;
  $('error').classList.remove('hidden');
}

function renderTerm(t) {
  const li = el('li', 'p-3 rounded-xl border border-slate-200');
  const head = el('div', 'flex flex-wrap items-center gap-2');
  const link = el('a', 'font-bold text-lg hover:underline', t.term);
  link.href = '/t/' + encodeURIComponent(t.slug);
  head.append(link, el('span', 'text-xs font-semibold px-2 py-1 rounded-full risk-' + t.risk, t.risk.toLowerCase()));
  if (t.verified) head.append(el('span', 'text-xs font-semibold px-2 py-1 rounded-full bg-slate-100 text-slate-700', '✔ Verified code'));
  li.append(head, el('p', 'mt-1', t.meaning));
  if (t.why) li.append(el('p', 'mt-1 text-sm text-slate-500', t.why));
  return li;
}

function renderResult(r) {
  const overall = $('overall');
  overall.className = 'p-4 rounded-xl font-bold text-lg risk-' + r.overallRisk;
  overall.textContent = RISK_LABEL[r.overallRisk];
  $('translation').textContent = r.translation;
  $('terms').replaceChildren(...r.terms.map(renderTerm));
  $('how').textContent = r.howToTalk;
  $('crisis').classList.toggle('hidden', r.overallRisk !== 'RED');
  $('result').classList.remove('hidden');
  if (r.remaining != null && $('remaining')) {
    $('remaining').textContent = r.remaining + ' free decode' + (r.remaining === 1 ? '' : 's') + ' left today';
  }
}

$('decode-form').addEventListener('submit', async (e) => {
  e.preventDefault();
  $('error').classList.add('hidden');
  const text = $('text').value.trim();
  const image = $('image').files[0];
  if (!text && !image) return showError('Paste a message or upload a screenshot.');

  const body = new FormData();
  if (text) body.append('text', text);
  if (image) body.append('image', image);

  const btn = $('decode-btn');
  btn.disabled = true;
  btn.textContent = 'Decoding…';
  try {
    const res = await fetch('/api/decode', { method: 'POST', body });
    const data = await res.json().catch(() => ({ error: 'Something went wrong. Try again.' }));
    if (res.status === 402 && data.paywall) return openPremium(data.error);
    if (!res.ok) return showError(data.error);
    renderResult(data);
    $('result').scrollIntoView({ behavior: 'smooth', block: 'start' });
  } catch {
    showError('Network error. Check your connection and try again.');
  } finally {
    btn.disabled = false;
    btn.textContent = 'Decode';
  }
});

// ---------- Subscribe ----------
$('subscribe-form').addEventListener('submit', async (e) => {
  e.preventDefault();
  const msg = $('subscribe-msg');
  try {
    const res = await fetch('/api/subscribe', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email: $('email').value }),
    });
    const data = await res.json();
    msg.textContent = res.ok ? "You're in. This week's brief is on its way." : data.error;
  } catch {
    msg.textContent = 'Network error. Try again.';
  }
});
