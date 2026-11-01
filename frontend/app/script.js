const reportArea = document.getElementById('report');
const form = document.getElementById('reportForm');
const photoInput = document.getElementById('photoInput');
const uploadZone = document.getElementById('uploadZone');
const preview = document.getElementById('imagePreview');
const description = document.getElementById('descriptionInput');
const characterCount = document.getElementById('characterCount');
const toast = document.getElementById('toast');
const authScreen = document.getElementById('authScreen');
const appFrame = document.querySelector('.app-frame');
const authForm = document.getElementById('authForm');
const municipalPanel = document.getElementById('municipalPanel');
const voterId = getVoterId();
let communityReports = [];
let municipalReports = [];
let liveMap = null;
let reportMarkers = [];
let mapStarted = false;
let registrationMode = false;
let employeeLoginMode = false;
let reportRefreshTimer = null;

function getVoterId() {
  const key = 'civicsignal-voter-id';
  let id = localStorage.getItem(key);
  if (!id) {
    id = crypto.randomUUID();
    localStorage.setItem(key, id);
  }
  return id;
}

document.getElementById('useMyLocation').addEventListener('click', () => {
  const button = document.getElementById('useMyLocation');
  const status = document.getElementById('locationStatus');
  if (!navigator.geolocation) {
    status.textContent = 'Location is not available in this browser.';
    return;
  }
  button.disabled = true;
  button.textContent = 'Finding location...';
  navigator.geolocation.getCurrentPosition(position => {
    document.getElementById('latitudeInput').value = position.coords.latitude;
    document.getElementById('longitudeInput').value = position.coords.longitude;
    status.textContent = 'GPS coordinates added; this report will appear on the map.';
    button.textContent = 'Location added';
    button.disabled = false;
  }, () => {
    status.textContent = 'Could not get GPS. Allow location access or enter the address above.';
    button.textContent = 'Use my location';
    button.disabled = false;
  }, { enableHighAccuracy: true, timeout: 10000, maximumAge: 60000 });
});

function startLiveMap() {
  if (!window.L) return;
  const fallback = [18.5204, 73.8567]; // Default map view only; reports do not store coordinates yet.
  liveMap = L.map('liveMap', { zoomControl: false, scrollWheelZoom: false }).setView(fallback, 13);
  L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
    maxZoom: 19,
    attribution: '&copy; OpenStreetMap contributors'
  }).addTo(liveMap);

  const label = document.getElementById('locateButton');
  const userIcon = L.divIcon({ className: '', html: '<div class="user-marker"></div>', iconSize: [17, 17], iconAnchor: [8, 8] });
  let userMarker;
  document.getElementById('zoomIn').addEventListener('click', () => liveMap.setZoom(liveMap.getZoom() + 1, { animate: true }));
  document.getElementById('zoomOut').addEventListener('click', () => liveMap.setZoom(liveMap.getZoom() - 1, { animate: true }));
  window.addEventListener('resize', () => liveMap.invalidateSize());
  setTimeout(() => liveMap.invalidateSize(), 300);

  label.addEventListener('click', () => {
    if (!navigator.geolocation) {
      label.textContent = 'Location unavailable · report addresses below';
      return;
    }
    label.textContent = 'Finding your location…';
    navigator.geolocation.getCurrentPosition(position => {
      const userLocation = [position.coords.latitude, position.coords.longitude];
      liveMap.setView(userLocation, 14, { animate: true });
      if (userMarker) userMarker.remove();
      userMarker = L.marker(userLocation, { icon: userIcon }).addTo(liveMap).bindPopup('Your location');
      label.textContent = `${reportMarkers.length} mapped reports · showing your area`;
    }, () => {
      label.textContent = `${reportMarkers.length} mapped reports · location unavailable`;
    }, { enableHighAccuracy: true, timeout: 8000, maximumAge: 60000 });
  });
}

document.querySelectorAll('[data-scroll-report]').forEach(button => {
  button.addEventListener('click', () => reportArea.scrollIntoView({ behavior: 'smooth' }));
});

document.querySelectorAll('[data-scroll-community]').forEach(link => {
  const goToCommunity = () => document.getElementById('community').scrollIntoView({ behavior: 'smooth', block: 'start' });
  link.addEventListener('click', goToCommunity);
  link.addEventListener('keydown', event => {
    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault();
      goToCommunity();
    }
  });
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
  button.textContent = 'Publishing...';
  try {
    const data = new FormData();
    data.append('issueType', document.getElementById('issueType').value);
    data.append('location', document.getElementById('locationInput').value.trim());
    data.append('description', description.value.trim());
    const latitude = document.getElementById('latitudeInput').value;
    const longitude = document.getElementById('longitudeInput').value;
    if (latitude && longitude) {
      data.append('latitude', latitude);
      data.append('longitude', longitude);
    }
    if (photoInput.files[0]) data.append('photo', photoInput.files[0]);
    const response = await fetch('/api/reports', { method: 'POST', body: data });
    const report = await response.json();
    if (!response.ok) throw new Error(report.error || 'Unable to publish this report.');
    communityReports.unshift(report);
    renderCommunityActivity(communityReports);
    publishReport(report, true);
    toast.classList.add('show');
    form.reset(); uploadZone.classList.remove('has-image'); characterCount.textContent = '0';
    document.getElementById('locationStatus').textContent = 'Add GPS coordinates to show this report on the map.';
    document.getElementById('useMyLocation').textContent = 'Use my location';
    setTimeout(() => toast.classList.remove('show'), 4500);
  } catch (error) {
    alert(error.message || 'Unable to publish this report. Start the site with mvn compile exec:java.');
  } finally {
    button.disabled = false;
    button.innerHTML = 'Publish report <span>&rarr;</span>';
  }
});

async function likeReport(button) {
  const post = button.closest('.post-card');
  if (!post.dataset.reportId) return;
  const action = post.querySelector('.post-actions [data-like]');
  const likeButtons = [...post.querySelectorAll('[data-like]')];
  if (likeButtons.some(likeButton => likeButton.disabled)) return;
  likeButtons.forEach(likeButton => likeButton.disabled = true);
  const count = action.querySelector('b');
  const previousLiked = action.classList.contains('liked');
  const liked = action.classList.toggle('liked');
  count.textContent = Number(count.textContent) + (liked ? 1 : -1);
  action.firstChild.textContent = liked ? '\u2665 ' : '\u2661 ';
  post.querySelector('.image-heart').textContent = liked ? '\u2665' : '\u2661';
  post.querySelector('.image-heart').classList.toggle('liked', liked);
  try {
    const response = await fetch(`/api/reports/${post.dataset.reportId}/like`, {
      method: 'POST',
      headers: { 'X-Voter-Id': voterId }
    });
    const result = await response.json();
    if (!response.ok) throw new Error(result.error || 'Unable to update support.');
    action.classList.toggle('liked', result.liked);
    count.textContent = result.likes;
    action.firstChild.textContent = result.liked ? '\u2665 ' : '\u2661 ';
    post.querySelector('.image-heart').textContent = result.liked ? '\u2665' : '\u2661';
    post.querySelector('.image-heart').classList.toggle('liked', result.liked);
    const savedReport = communityReports.find(report => String(report.id) === String(post.dataset.reportId));
    if (savedReport) {
      savedReport.likes = result.likes;
      savedReport.liked = result.liked;
      renderCommunityActivity(communityReports);
    }
  } catch (error) {
    action.classList.toggle('liked', previousLiked);
    count.textContent = Number(count.textContent) + (liked ? -1 : 1);
    action.firstChild.textContent = previousLiked ? '\u2665 ' : '\u2661 ';
    post.querySelector('.image-heart').textContent = previousLiked ? '\u2665' : '\u2661';
    post.querySelector('.image-heart').classList.toggle('liked', previousLiked);
    alert(error.message || 'Unable to update support.');
  } finally {
    likeButtons.forEach(likeButton => likeButton.disabled = false);
  }
}

async function addComment(event) {
  event.preventDefault();
  const commentForm = event.currentTarget;
  const input = commentForm.querySelector('input');
  const message = input.value.trim();
  if (!message) return;
  const post = commentForm.closest('.post-card');
  let result;
  try {
    const response = await fetch(`/api/reports/${post.dataset.reportId}/comments`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ body: message }) });
    result = await response.json();
    if (!response.ok) throw new Error(result.error || 'Unable to post your comment.');
  } catch (error) {
    alert(error.message || 'Unable to post your comment.');
    return;
  }
  const item = document.createElement('p');
  const author = document.createElement('strong');
  author.textContent = 'You';
  item.append(author, ` ${message}`);
  const savedReport = communityReports.find(report => String(report.id) === String(post.dataset.reportId));
  if (savedReport) {
    savedReport.comments = savedReport.comments || [];
    savedReport.comments.push(result);
    renderCommunityActivity(communityReports);
  }
  commentForm.before(item);
  const count = post.querySelector('[data-comment] b');
  count.textContent = Number(count.textContent) + 1;
  input.value = '';
}

function bindPostInteractions(post) {
  post.querySelectorAll('[data-like]').forEach(button => button.addEventListener('click', () => likeReport(button)));
  const image = post.querySelector('.post-image');
  if (image) {
    image.tabIndex = 0;
    image.setAttribute('role', 'button');
    image.setAttribute('aria-label', 'Open full-size report photo');
    image.addEventListener('click', event => {
      if (!event.target.closest('[data-like]')) openPhoto(image);
    });
    image.addEventListener('keydown', event => {
      if (event.target === image && (event.key === 'Enter' || event.key === ' ')) {
        event.preventDefault();
        openPhoto(image);
      }
    });
  }
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
  if (report.image_url) {
    image.dataset.fullImageUrl = report.image_url;
    image.style.backgroundImage = `linear-gradient(#00000008,#00000012), url("${report.image_url}")`;
  }
  const liked = report.liked === true;
  const status = normalizedReportStatus(report.municipal_status);
  const statusLabel = status === 'sent' ? 'Sent to municipality' : status === 'under review' ? 'Under review' : status === 'resolved' ? 'Resolved' : 'Needs attention';
  image.innerHTML = `<span class="status-pill">${statusLabel}</span><button type="button" class="image-heart${liked ? ' liked' : ''}" data-like aria-label="Like report">${liked ? '\u2665' : '\u2661'}</button>`;
  const content = document.createElement('div');
  content.className = 'post-content';

  content.innerHTML = '<div class="post-meta"><span></span><time>Recently</time></div><h3></h3><p class="location"></p><div class="post-actions"><button data-like>\u2661 <b>0</b> support</button><button data-comment>\u25cc <b>0</b> comments</button><button class="share-button" aria-label="Share report">\u2197</button></div><div class="comments"><form data-comment-form><input aria-label="Add a comment" placeholder="Add your voice..." /><button>Post</button></form></div>';
  content.querySelector('.post-meta span').textContent = report.issue_type;
  content.querySelector('h3').textContent = report.description;
  content.querySelector('.location').textContent = `\u2316 ${report.location}`;
  content.querySelector('[data-like] b').textContent = report.likes || 0;
  content.querySelector('[data-like]').classList.toggle('liked', liked);
  content.querySelector('[data-like]').firstChild.textContent = liked ? '\u2665 ' : '\u2661 ';
  content.querySelector('[data-comment] b').textContent = (report.comments || []).length;
  content.querySelectorAll('button').forEach(button => button.type = button.closest('form') ? 'submit' : 'button');
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

let reportSnapshot = null;
async function loadReports() {
  const response = await fetch('/api/reports', { headers: { 'X-Voter-Id': voterId } });
  if (!response.ok) throw new Error('Could not load reports.');
  const reports = await response.json();
  const snapshot = JSON.stringify(reports);
  if (snapshot === reportSnapshot) return;
  const feed = document.getElementById('feed');
  const cardState = new Map([...feed.querySelectorAll('.post-card')].map(post => [post.dataset.reportId, {
    commentsOpen: post.querySelector('.comments')?.classList.contains('open') || false,
    draft: post.querySelector('[data-comment-form] input')?.value || ''
  }]));
  communityReports = reports;
  renderCommunityActivity(reports);
  feed.innerHTML = '';
  reports.forEach(report => {
    publishReport(report);
    const post = feed.querySelector(`[data-report-id="${report.id}"]`);
    const savedState = cardState.get(String(report.id));
    if (savedState) {
      const comments = post.querySelector('.comments');
      comments.classList.toggle('open', savedState.commentsOpen);
      post.querySelector('[data-comment-form] input').value = savedState.draft;
    }
  });
  reportSnapshot = snapshot;
}
async function enterApp() {
  municipalPanel.hidden = true;
  authScreen.hidden = true;
  appFrame.hidden = false;
  if (!mapStarted) {
    startLiveMap();
    mapStarted = true;
  }
  setTimeout(() => liveMap?.invalidateSize(), 100);
  try {
    await loadReports();
  } catch {
    document.getElementById('feed').innerHTML = '<p class="feed-status feed-error">Reports could not load. Check your connection and refresh.</p>';
  }
  if (!reportRefreshTimer) reportRefreshTimer = window.setInterval(() => loadReports().catch(() => {}), 20000);
}

async function enterMunicipalApp(session) {
  if (reportRefreshTimer) window.clearInterval(reportRefreshTimer);
  reportRefreshTimer = null;
  authScreen.hidden = true;
  appFrame.hidden = true;
  municipalPanel.hidden = false;
  document.getElementById('municipalUserName').textContent = session.name || 'Corporation employee';
  await loadMunicipalReports();
  if (!reportRefreshTimer) reportRefreshTimer = window.setInterval(() => loadMunicipalReports().catch(() => {}), 20000);
}

const reportStatuses = [
  ['queued', 'Queued'],
  ['under review', 'Under review'],
  ['sent', 'Sent to municipality'],
  ['resolved', 'Resolved']
];

function normalizedReportStatus(status) {
  const value = String(status || 'queued').toLowerCase();
  if (value === 'needs attention' || value === 'needs_attention') return 'queued';
  if (value === 'sent to municipality') return 'sent';
  return reportStatuses.some(([key]) => key === value) ? value : 'queued';
}

function formatReportDate(value) {
  if (!value) return 'Date unavailable';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? 'Date unavailable' : date.toLocaleString();
}

async function loadMunicipalReports() {
  const response = await fetch('/api/admin/reports');
  const result = await response.json();
  if (!response.ok) throw new Error(result.error || 'Could not load reports.');
  municipalReports = result;
  renderMunicipalReports();
}

function renderMunicipalReports() {
  const total = municipalReports.length;
  const resolved = municipalReports.filter(report => normalizedReportStatus(report.municipal_status) === 'resolved').length;
  document.getElementById('adminTotalReports').textContent = total.toLocaleString();
  document.getElementById('adminOpenReports').textContent = (total - resolved).toLocaleString();
  document.getElementById('adminResolvedReports').textContent = resolved.toLocaleString();

  const filter = document.getElementById('adminStatusFilter').value;
  const query = document.getElementById('adminSearch').value.trim().toLowerCase();
  const visible = municipalReports.filter(report => {
    const matchesStatus = filter === 'all' || normalizedReportStatus(report.municipal_status) === filter;
    const searchable = `${report.id} ${report.issue_type || ''} ${report.location || ''} ${report.description || ''}`.toLowerCase();
    return matchesStatus && (!query || searchable.includes(query));
  });
  const list = document.getElementById('adminReportList');
  list.replaceChildren();
  if (!visible.length) {
    const empty = document.createElement('p');
    empty.className = 'municipal-empty';
    empty.textContent = total ? 'No reports match these filters.' : 'No community reports have been submitted yet.';
    list.append(empty);
    return;
  }

  visible.forEach(report => {
    const card = document.createElement('article');
    card.className = 'municipal-report-card';
    const main = document.createElement('div');
    main.className = 'municipal-report-main';
    const title = document.createElement('h2');
    title.textContent = `${report.issue_type || 'Civic issue'} · #${report.id}`;
    const meta = document.createElement('p');
    meta.className = 'municipal-report-meta';
    const location = document.createElement('span');
    location.textContent = report.location || 'Location not provided';
    const created = document.createElement('span');
    created.textContent = formatReportDate(report.created_at);
    meta.append(location, created);
    const descriptionText = document.createElement('p');
    descriptionText.className = 'municipal-report-description';
    descriptionText.textContent = report.description || 'No description provided.';
    const activity = document.createElement('p');
    activity.className = 'municipal-report-activity';
    activity.textContent = `${Number(report.likes) || 0} supports · ${(report.comments || []).length} comments`;
    main.append(title, meta, descriptionText, activity);

    const controls = document.createElement('div');
    controls.className = 'municipal-status-control';
    const statusLabel = document.createElement('label');
    statusLabel.textContent = 'Report status';
    const select = document.createElement('select');
    select.setAttribute('aria-label', `Update status for report ${report.id}`);
    const currentStatus = normalizedReportStatus(report.municipal_status);
    reportStatuses.forEach(([value, label]) => {
      const option = document.createElement('option');
      option.value = value;
      option.textContent = label;
      option.selected = value === currentStatus;
      select.append(option);
    });
    statusLabel.append(select);
    const updateButton = document.createElement('button');
    updateButton.type = 'button';
    updateButton.textContent = 'Update status';
    updateButton.addEventListener('click', async () => {
      updateButton.disabled = true;
      updateButton.textContent = 'Saving...';
      try {
        const response = await fetch(`/api/admin/reports/${report.id}/status`, {
          method: 'PATCH', headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ status: select.value })
        });
        const updated = await response.json();
        if (!response.ok) throw new Error(updated.error || 'Could not update this report.');
        municipalReports = municipalReports.map(item => item.id === updated.id ? updated : item);
        renderMunicipalReports();
      } catch (error) {
        updateButton.disabled = false;
        updateButton.textContent = 'Update status';
        alert(error.message || 'Could not update this report.');
      }
    });
    controls.append(statusLabel, updateButton);
    card.append(main, controls);

    if (report.image_url) {
      const image = document.createElement('img');
      image.className = 'municipal-report-image';
      image.src = report.image_url;
      image.alt = `${report.issue_type || 'Issue'} at ${report.location || 'reported location'}`;
      image.loading = 'lazy';
      card.append(image);
    }
    if (report.status_updated_at) {
      const note = document.createElement('p');
      note.className = 'municipal-status-note';
      note.textContent = `Last updated ${formatReportDate(report.status_updated_at)}${report.status_updated_by ? ` by ${report.status_updated_by}` : ''}`;
      card.append(note);
    }
    list.append(card);
  });
}

document.getElementById('adminStatusFilter').addEventListener('change', renderMunicipalReports);
document.getElementById('adminSearch').addEventListener('input', renderMunicipalReports);
document.getElementById('adminRefreshButton').addEventListener('click', () => loadMunicipalReports().catch(error => alert(error.message)));

function showAuthError(message) {
  const error = document.getElementById('authError');
  error.textContent = message;
  error.hidden = false;
}

function setRegistrationMode(enabled) {
  registrationMode = enabled && !employeeLoginMode;
  enabled = registrationMode;
  document.getElementById('authNameField').hidden = !enabled;
  document.getElementById('authName').required = enabled;
  document.getElementById('authTitle').innerHTML = employeeLoginMode ? 'Corporation<br /><em>sign in.</em>' : enabled ? 'Create your<br /><em>account.</em>' : 'Welcome<br /><em>back.</em>';
  document.getElementById('authIntro').textContent = employeeLoginMode
    ? 'Sign in to review community reports and update their progress.'
    : 'Sign in to report local issues, support your neighbours, and follow community updates.';
  document.getElementById('authSubmit').textContent = enabled ? 'Create account' : 'Sign in';
  document.getElementById('authSwitchPrompt').textContent = enabled ? 'Already have an account?' : 'New to CivicSignal?';
  document.getElementById('authModeToggle').textContent = enabled ? 'Sign in' : 'Create an account';
  document.getElementById('authSignupSwitch').hidden = employeeLoginMode;
  document.getElementById('authPortalToggle').textContent = employeeLoginMode ? 'Resident sign in' : 'Corporation employee sign in';
  document.getElementById('authPassword').autocomplete = enabled ? 'new-password' : 'current-password';
  document.getElementById('authError').hidden = true;
  document.getElementById('authNotice').hidden = true;
}

document.getElementById('authModeToggle').addEventListener('click', () => setRegistrationMode(!registrationMode));
document.getElementById('authPortalToggle').addEventListener('click', () => {
  employeeLoginMode = !employeeLoginMode;
  setRegistrationMode(false);
});
authForm.addEventListener('submit', async event => {
  event.preventDefault();
  const submit = document.getElementById('authSubmit');
  const error = document.getElementById('authError');
  error.hidden = true;
  submit.disabled = true;
  submit.textContent = registrationMode ? 'Creating account...' : 'Signing in...';
  const payload = {
    email: document.getElementById('authEmail').value.trim(),
    password: document.getElementById('authPassword').value,
    portal: employeeLoginMode ? 'municipal' : 'resident'
  };
  if (registrationMode) payload.name = document.getElementById('authName').value.trim();
  try {
    const response = await fetch(registrationMode ? '/api/auth/register' : '/api/auth/login', {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload)
    });
    const result = await response.json();
    if (!response.ok) {
      const message = response.status === 404
        ? 'The backend is running an older version. Stop its terminal with Ctrl+C, then restart it with the command below.'
        : result.error || 'Authentication failed.';
      throw new Error(message);
    }
    if (registrationMode) {
      authForm.reset();
      setRegistrationMode(false);
      document.getElementById('authEmail').value = payload.email;
      const notice = document.getElementById('authNotice');
      notice.textContent = 'Account created. Please sign in with your email and password.';
      notice.hidden = false;
      document.getElementById('authPassword').focus();
      return;
    }
    authForm.reset();
    if (result.role === 'municipal') await enterMunicipalApp(result);
    else await enterApp();
  } catch (err) {
    showAuthError(err.message || 'Could not connect to the CivicSignal server.');
  } finally {
    submit.disabled = false;
    submit.textContent = registrationMode ? 'Create account' : 'Sign in';
  }
});

async function signOut() {
  try { await fetch('/api/auth/logout', { method: 'POST' }); } finally {
    if (reportRefreshTimer) window.clearInterval(reportRefreshTimer);
    reportRefreshTimer = null;
    reportSnapshot = null;
    appFrame.hidden = true;
    municipalPanel.hidden = true;
    authScreen.hidden = false;
    employeeLoginMode = false;
    setRegistrationMode(false);
  }
}
document.getElementById('logoutButton').addEventListener('click', signOut);
document.getElementById('municipalLogoutButton').addEventListener('click', signOut);

async function restoreSession() {
  const response = await fetch('/api/auth/session');
  if (!response.ok) throw new Error('Could not verify your session.');
  const session = await response.json();
  if (session.authenticated) {
    if (session.role === 'municipal') await enterMunicipalApp(session);
    else await enterApp();
  }
}
restoreSession().catch(() => showAuthError('Cannot reach the server. Start the backend and open CivicSignal at http://localhost:8000.'));

function renderCommunityActivity(reports) {
  const comments = reports.reduce((total, report) => total + (report.comments || []).length, 0);
  const supports = reports.reduce((total, report) => total + (Number(report.likes) || 0), 0);
  const locations = new Set(reports.map(report => (report.location || '').trim().toLowerCase()).filter(Boolean)).size;
  renderIssueChips(reports);
  const typeCounts = reports.reduce((counts, report) => {
    const type = report.issue_type || 'Other';
    counts[type] = (counts[type] || 0) + 1;
    return counts;
  }, {});
  const topCount = Math.max(0, ...Object.values(typeCounts));
  const topTypes = Object.entries(typeCounts).filter(([, count]) => count === topCount).map(([type]) => type);
  document.getElementById('pulseReportCount').textContent = reports.length.toLocaleString();
  document.getElementById('pulseTopIssue').textContent = topTypes.length > 1 ? `Tie across ${topTypes.length} types` : topTypes[0] || 'No reports yet';
  document.getElementById('pulseSupportCount').textContent = supports.toLocaleString();
  document.getElementById('statReports').textContent = reports.length.toLocaleString();
  document.getElementById('statLocations').textContent = locations.toLocaleString();
  document.getElementById('statSupports').textContent = supports.toLocaleString();
  document.getElementById('statComments').textContent = comments.toLocaleString();
  document.getElementById('nearbyReportCount').textContent = `${reports.length} report${reports.length === 1 ? '' : 's'} in the feed`;
  renderHeroActivity(reports);
  renderReportMap(reports);
  document.getElementById('voiceSummary').textContent = `${comments.toLocaleString()} comments | ${supports.toLocaleString()} supports`;

  const wall = document.getElementById('voiceWall');
  wall.replaceChildren();
  if (!reports.length) {
    const empty = document.createElement('p');
    empty.className = 'feed-status';
    empty.textContent = 'No reports or comments are in the database yet. Submit the first report to start the conversation.';
    wall.append(empty);
    return;
  }

  reports.slice(0, 2).forEach((report, index) => {
    const recentComment = (report.comments || []).at(-1);
    const card = document.createElement('article');
    card.className = `quote-card ${index === 0 ? 'featured' : 'small-card'}`;
    const marker = document.createElement('span');
    marker.className = 'quote-mark';
    marker.textContent = recentComment ? '\u201c' : '\u2022';
    const message = document.createElement('p');
    message.textContent = recentComment ? recentComment.body : report.description;
    const footer = document.createElement('footer');
    const avatar = document.createElement('span');
    avatar.className = 'mini-avatar';
    avatar.textContent = recentComment ? (recentComment.author || 'N').slice(0, 1) : 'R';
    const attribution = document.createElement('div');
    const author = document.createElement('strong');
    author.textContent = recentComment ? recentComment.author : `Report #${report.id}`;
    const location = document.createElement('small');
    location.textContent = report.location || 'Location not provided';
    attribution.append(author, location);
    const status = document.createElement('em');
    status.textContent = report.municipal_status || 'queued';
    footer.append(avatar, attribution, status);
    card.append(marker, message, footer);
    wall.append(card);
  });

  const summary = document.createElement('div');
  summary.className = 'resolution-card';
  const count = document.createElement('span');
  count.textContent = reports.length.toLocaleString();
  const label = document.createElement('strong');
  label.textContent = `report${reports.length === 1 ? '' : 's'} in the database`;
  const detail = document.createElement('p');
  detail.textContent = `${locations.toLocaleString()} reported locations | ${supports.toLocaleString()} supports | ${comments.toLocaleString()} comments`;
  summary.append(count, label, detail);
  wall.append(summary);
}

function renderIssueChips(reports) {
  const chips = document.getElementById('issueChips');
  const counts = new Map();
  for (const report of reports) {
    const type = (report.issue_type || 'Other').toLowerCase();
    const category = type.includes('road') || type.includes('pothole') ? 'Road safety'
      : type.includes('streetlight') ? 'Streetlights'
        : type.includes('footpath') ? 'Footpaths'
          : type.includes('waste') || type.includes('sanitation') ? 'Sanitation'
            : report.issue_type || 'Other';
    counts.set(category, (counts.get(category) || 0) + 1);
  }
  chips.replaceChildren();
  if (!counts.size) {
    const empty = document.createElement('span');
    empty.textContent = 'No issue categories yet';
    chips.append(empty);
    return;
  }
  [...counts.entries()].sort((a, b) => b[1] - a[1]).forEach(([category, count]) => {
    const chip = document.createElement('span');
    chip.textContent = `${category} | ${count}`;
    chips.append(chip);
  });
}

let mapMarkerSignature = '';
function renderReportMap(reports) {
  if (!liveMap || !window.L) return;
  const mapped = reports.filter(report => report.latitude !== null && report.latitude !== undefined
    && report.longitude !== null && report.longitude !== undefined
    && Number.isFinite(Number(report.latitude)) && Number.isFinite(Number(report.longitude)));
  const signature = mapped.map(report => `${report.id}:${report.latitude}:${report.longitude}`).join('|');
  if (signature !== mapMarkerSignature) {
    reportMarkers.forEach(marker => marker.remove());
    const icon = L.divIcon({ className: '', html: '<div class="issue-marker"><span>!</span></div>', iconSize: [25, 25], iconAnchor: [12, 25] });
    reportMarkers = mapped.map(report => {
      const marker = L.marker([Number(report.latitude), Number(report.longitude)], { icon }).addTo(liveMap);
      const popup = document.createElement('div');
      const title = document.createElement('strong');
      title.textContent = report.issue_type || 'Civic report';
      const details = document.createElement('p');
      details.textContent = `${report.location || 'Location'}: ${report.description || ''}`;
      popup.append(title, details);
      marker.bindPopup(popup);
      return marker;
    });
    mapMarkerSignature = signature;
    if (mapped.length) {
      const bounds = L.featureGroup(reportMarkers).getBounds();
      liveMap.fitBounds(bounds, { padding: [24, 24], maxZoom: 14 });
    }
  }
  const label = document.getElementById('locateButton');
  if (label && !label.textContent.includes('showing your area') && !label.textContent.includes('location unavailable')) {
    label.textContent = mapped.length
      ? `${mapped.length} mapped report${mapped.length === 1 ? '' : 's'} · click a pin`
      : 'No mapped reports yet · add GPS in the form';
  }
}

function renderHeroActivity(reports) {
  const total = reports.length;
  document.getElementById('heroReportCount').textContent = `${total.toLocaleString()} report${total === 1 ? '' : 's'}`;
  const mapLabel = document.getElementById('locateButton');
  if (mapLabel) {
    const state = mapLabel.textContent.includes('showing your area') ? 'showing your area'
      : mapLabel.textContent.includes('location unavailable') ? 'location unavailable' : 'addresses below';
    mapLabel.textContent = `${total.toLocaleString()} reports · ${state}`;
  }

  const latest = reports[0];
  const image = document.getElementById('heroPreviewImage');
  const noPhoto = document.getElementById('heroPreviewNoPhoto');
  if (!latest) {
    document.getElementById('heroPreviewStatus').textContent = 'No reports yet';
    document.getElementById('heroPreviewTitle').textContent = 'Be the first to report an issue';
    document.getElementById('heroPreviewLocation').textContent = 'Community feed is empty';
    document.getElementById('heroPreviewLikes').textContent = '♡ 0';
    document.getElementById('heroPreviewComments').textContent = '◌ 0';
    document.getElementById('heroLatestStatus').textContent = 'No report submitted';
    image.hidden = true;
    noPhoto.hidden = false;
    return;
  }

  const description = (latest.description || latest.issue_type || 'Civic issue reported').trim();
  document.getElementById('heroPreviewStatus').textContent = latest.issue_type || 'Latest report';
  document.getElementById('heroPreviewTitle').textContent = description.length > 58 ? `${description.slice(0, 55)}…` : description;
  document.getElementById('heroPreviewLocation').textContent = latest.location || 'Location not provided';
  document.getElementById('heroPreviewLikes').textContent = `♡ ${Number(latest.likes) || 0}`;
  document.getElementById('heroPreviewComments').textContent = `◌ ${(latest.comments || []).length}`;
  document.getElementById('heroLatestStatus').textContent = latest.municipal_status || 'queued';
  if (latest.image_url) {
    image.src = latest.image_url;
    image.alt = description;
    image.hidden = false;
    noPhoto.hidden = true;
    image.onerror = () => { image.hidden = true; noPhoto.hidden = false; };
  } else {
    image.hidden = true;
    noPhoto.hidden = false;
  }
}

let photoScrollPosition = 0;
function openPhoto(postImage) {
  const viewer = document.getElementById('photoViewer');
  const fullImage = viewer.querySelector('img');
  let source = postImage.dataset.fullImageUrl;
  if (!source) {
    const matches = [...getComputedStyle(postImage).backgroundImage.matchAll(/url\(["']?([^"')]+)["']?\)/g)];
    source = matches.at(-1)?.[1];
  }
  if (!source) return;
  photoScrollPosition = window.scrollY;
  fullImage.src = source;
  viewer.hidden = false;
  viewer.querySelector('.photo-viewer-close').focus({ preventScroll: true });
  window.scrollTo(0, photoScrollPosition);
}

const photoViewer = document.getElementById('photoViewer');
function closePhoto() {
  photoViewer.hidden = true;
  photoViewer.querySelector('img').removeAttribute('src');
  window.scrollTo(0, photoScrollPosition);
}
photoViewer.querySelector('.photo-viewer-close').addEventListener('click', closePhoto);
photoViewer.addEventListener('click', event => { if (event.target === photoViewer) closePhoto(); });
document.addEventListener('keydown', event => { if (event.key === 'Escape' && !photoViewer.hidden) closePhoto(); });

// Keep the section surfaces while leaving scrolling under the visitor's control.
document.body.classList.add('cylindrical-scroll');
document.querySelectorAll('.app-frame main > section').forEach(section => section.classList.add('cylinder-card'));
