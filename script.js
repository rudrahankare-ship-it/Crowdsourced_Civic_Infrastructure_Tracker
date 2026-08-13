const reportArea = document.getElementById('report');
const form = document.getElementById('reportForm');
const photoInput = document.getElementById('photoInput');
const uploadZone = document.getElementById('uploadZone');
const preview = document.getElementById('imagePreview');
const description = document.getElementById('descriptionInput');
const characterCount = document.getElementById('characterCount');
const toast = document.getElementById('toast');

startLiveMap();

function startLiveMap() {
  if (!window.L) return;
  const fallback = [18.5204, 73.8567]; // Pune fallback when location permission is not granted.
  const map = L.map('liveMap', { zoomControl: false, scrollWheelZoom: false }).setView(fallback, 13);
  L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
    maxZoom: 19,
    attribution: '&copy; OpenStreetMap contributors'
  }).addTo(map);

  const label = document.getElementById('locateButton');
  const issueIcon = L.divIcon({ className: '', html: '<div class="issue-marker"><span>!</span></div>', iconSize: [25, 25], iconAnchor: [12, 25] });
  const userIcon = L.divIcon({ className: '', html: '<div class="user-marker"></div>', iconSize: [17, 17], iconAnchor: [8, 8] });
  let currentPosition = fallback;
  let markers = [];

  document.getElementById('zoomIn').addEventListener('click', () => map.setZoom(map.getZoom() + 1, { animate: true }));
  document.getElementById('zoomOut').addEventListener('click', () => map.setZoom(map.getZoom() - 1, { animate: true }));
  window.addEventListener('resize', () => map.invalidateSize());
  setTimeout(() => map.invalidateSize(), 300);

  function showNearbyIssues(position) {
    currentPosition = position;
    markers.forEach(marker => marker.remove());
    const reports = [
      { offset: [.006, -.005], type: 'Road damage', detail: 'Pothole reported by a neighbour' },
      { offset: [-.004, .007], type: 'Broken streetlight', detail: 'Streetlight needs attention' },
      { offset: [.003, .009], type: 'Footpath issue', detail: 'Damaged pavement reported' }
    ];
    markers = reports.map(report => L.marker([position[0] + report.offset[0], position[1] + report.offset[1]], { icon: issueIcon })
      .addTo(map).bindPopup(`<strong>${report.type}</strong><br>${report.detail}`));
    label.textContent = `${reports.length} active reports near you · locate me`;
  }

  showNearbyIssues(fallback);
  function locateUser() {
    label.textContent = 'Finding your location…';
    if (!navigator.geolocation) { label.textContent = 'Location is unavailable · showing Pune'; return; }
    navigator.geolocation.getCurrentPosition(position => {
      const userLocation = [position.coords.latitude, position.coords.longitude];
      map.setView(userLocation, 14, { animate: true });
      L.marker(userLocation, { icon: userIcon }).addTo(map).bindPopup('You are here').openPopup();
      showNearbyIssues(userLocation);
    }, () => { label.textContent = 'Showing reports around Pune · locate me'; }, { enableHighAccuracy: true, timeout: 8000, maximumAge: 60000 });
  }
  label.addEventListener('click', locateUser);
  locateUser();
}

document.querySelectorAll('[data-scroll-report]').forEach(button => {
  button.addEventListener('click', () => reportArea.scrollIntoView({ behavior: 'smooth' }));
});

photoInput.addEventListener('change', showPhotoPreview);
['dragenter', 'dragover'].forEach(type => uploadZone.addEventListener(type, event => {
  event.preventDefault();
  uploadZone.style.borderColor = '#176759';
}));
['dragleave', 'drop'].forEach(type => uploadZone.addEventListener(type, event => {
  event.preventDefault();
  uploadZone.style.borderColor = '';
}));
uploadZone.addEventListener('drop', event => {
  const file = event.dataTransfer.files[0];
  if (!file || !file.type.startsWith('image/')) return;
  photoInput.files = event.dataTransfer.files;
  preview.src = URL.createObjectURL(file);
  uploadZone.classList.add('has-image');
});

function showPhotoPreview() {
  const [file] = photoInput.files;
  if (!file) return;
  preview.src = URL.createObjectURL(file);
  uploadZone.classList.add('has-image');
}

description.addEventListener('input', () => characterCount.textContent = description.value.length);

form.addEventListener('submit', async event => {
  event.preventDefault();
  const button = form.querySelector('[type="submit"]');
  button.disabled = true;
  button.textContent = 'Publishing…';
  try {
    const data = new FormData();
    data.append('issueType', document.getElementById('issueType').value);
    data.append('location', document.getElementById('locationInput').value.trim());
    data.append('description', description.value.trim());
    if (photoInput.files[0]) data.append('photo', photoInput.files[0]);
    const response = await fetch('/api/reports', { method: 'POST', body: data });
    const report = await response.json();
    if (!response.ok) throw new Error(report.error || 'Unable to publish this report.');
    publishReport(report, true);
    toast.classList.add('show');
    form.reset(); uploadZone.classList.remove('has-image'); characterCount.textContent = '0';
    setTimeout(() => toast.classList.remove('show'), 4500);
  } catch (error) {
    alert(error.message || 'Unable to publish this report. Start the site with python server.py.');
  } finally {
    button.disabled = false;
    button.innerHTML = 'Publish report <span>→</span>';
  }
});

async function likeReport(button) {
  const post = button.closest('.post-card');
  const action = post.querySelector('.post-actions [data-like]');
  if (!post.dataset.reportId) return;
  const count = action.querySelector('b');
  const liked = action.classList.toggle('liked');
  count.textContent = Number(count.textContent) + (liked ? 1 : -1);
  action.firstChild.textContent = liked ? '\u2665 ' : '\u2661 ';
  post.querySelector('.image-heart').textContent = liked ? '\u2665' : '\u2661';
  post.querySelector('.image-heart').classList.toggle('liked', liked);
  try {
    const response = await fetch(`/api/reports/${post.dataset.reportId}/like`, { method: 'POST' });
    const result = await response.json();
    if (!response.ok) throw new Error();
    count.textContent = result.likes;
  } catch {
    action.classList.toggle('liked');
    count.textContent = Number(count.textContent) + (liked ? -1 : 1);
  }
}

async function addComment(event) {
  event.preventDefault();
  const commentForm = event.currentTarget;
  const input = commentForm.querySelector('input');
  const message = input.value.trim();
  if (!message) return;
  const post = commentForm.closest('.post-card');
  try {
    const response = await fetch(`/api/reports/${post.dataset.reportId}/comments`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ body: message }) });
    if (!response.ok) throw new Error();
  } catch { return; }
  const item = document.createElement('p');
  const author = document.createElement('strong');
  author.textContent = 'You';
  item.append(author, ` ${message}`);
  commentForm.before(item);
  input.value = '';
}

function bindPostInteractions(post) {
  post.querySelectorAll('[data-like]').forEach(button => button.addEventListener('click', () => likeReport(button)));
  post.querySelector('[data-comment]').addEventListener('click', event => {
    const comments = event.currentTarget.closest('.post-card').querySelector('.comments');
    comments.classList.toggle('open');
    if (comments.classList.contains('open')) comments.querySelector('input').focus();
  });
  post.querySelector('[data-comment-form]').addEventListener('submit', addComment);
}

function publishReport(report, scroll = false) {
  const post = document.createElement('article');
  post.className = 'post-card new-report';
  post.dataset.reportId = report.id;
  const image = document.createElement('div');
  const typeClass = report.issue_type === 'Streetlight' ? 'img-light' : report.issue_type === 'Footpath' ? 'img-walk' : 'img-road';
  image.className = `post-image ${report.image_url ? 'uploaded-photo' : typeClass}`;
  if (report.image_url) image.style.backgroundImage = `linear-gradient(#00000018,#00000045), url("${report.image_url}")`;
  image.innerHTML = `<span class="status-pill">${report.municipal_status === 'sent' ? 'Sent to municipality' : report.municipal_status === 'under review' ? 'Under review' : 'Needs attention'}</span><button class="image-heart" data-like aria-label="Like report">\u2661</button>`;
  const content = document.createElement('div');
  content.className = 'post-content';
  content.innerHTML = '<div class="post-meta"><span></span><time>Recently</time></div><h3></h3><p class="location"></p><div class="post-actions"><button data-like>\u2661 <b>0</b> support</button><button data-comment>\u25cc <b>0</b> comments</button><button class="share-button" aria-label="Share report">\u2197</button></div><div class="comments"><form data-comment-form><input aria-label="Add a comment" placeholder="Add your voice…" /><button>Post</button></form></div>';
  content.querySelector('.post-meta span').textContent = report.issue_type;
  content.querySelector('h3').textContent = report.description;
  content.querySelector('.location').textContent = `\u2316 ${report.location}`;
  content.querySelector('[data-like] b').textContent = report.likes || 0;
  content.querySelector('[data-comment] b').textContent = (report.comments || []).length;
  post.append(image, content);
  document.getElementById('feed').append(post);
  bindPostInteractions(post);
  (report.comments || []).forEach(comment => {
    const item = document.createElement('p'); const author = document.createElement('strong');
    author.textContent = comment.author; item.append(author, ` ${comment.body}`);
    post.querySelector('[data-comment-form]').before(item);
  });
  if (scroll) { document.getElementById('feed').prepend(post); post.scrollIntoView({ behavior: 'smooth', block: 'center' }); }
}

async function loadReports() {
  const response = await fetch('/api/reports');
  if (!response.ok) throw new Error('Could not load reports.');
  const reports = await response.json();
  const feed = document.getElementById('feed');
  feed.innerHTML = '';
  reports.forEach(report => publishReport(report));
}
loadReports().catch(() => document.querySelectorAll('.post-card').forEach(bindPostInteractions));

// The cylinder is deliberately static so scrolling stays smooth on every device.
document.body.classList.add('cylindrical-scroll');

// Lightweight inner-cylinder focus: only changes when a card reaches the viewport centre.
const cylinderCards = [...document.querySelectorAll('main > section')];
cylinderCards.forEach(card => card.classList.add('cylinder-card'));
if ('IntersectionObserver' in window && !window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
  const observer = new IntersectionObserver(entries => {
    entries.forEach(entry => entry.target.classList.toggle('in-cylinder-focus', entry.isIntersecting && entry.intersectionRatio > .48));
  }, { threshold: [.48], rootMargin: '-9% 0px -9% 0px' });
  cylinderCards.filter(card => !card.classList.contains('hero')).forEach(card => observer.observe(card));
}
