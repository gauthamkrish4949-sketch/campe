document.addEventListener('DOMContentLoaded', () => {
  document.querySelectorAll('[data-animate]').forEach((el, i) => {
    el.style.animationDelay = `${i * 80}ms`;
  });
  const clock = document.querySelector('[data-clock]');
  if (clock) {
    const tick = () => clock.textContent = new Date().toLocaleTimeString([], {hour:'2-digit', minute:'2-digit'});
    tick(); setInterval(tick, 1000);
  }
  document.querySelectorAll('[data-count]').forEach(el => {
    const target = Number(el.dataset.count || 0);
    let current = 0, steps = Math.max(1, Math.ceil(target / 30));
    const timer = setInterval(() => {
      current = Math.min(target, current + steps);
      el.textContent = current;
      if (current >= target) clearInterval(timer);
    }, 25);
  });
});

// Light / dark mode switcher. The preference is remembered in this browser.
(function () {
  const root = document.documentElement;
  const savedTheme = localStorage.getItem('campusone-theme');
  if (savedTheme === 'light') root.classList.add('light');

  function addThemeButton() {
    // Only render theme button on the main/home page
    const isHomePage = window.location.pathname === '/' || window.location.pathname === '/index' || window.location.pathname === '/index.html' || Boolean(document.querySelector('.hero'));
    if (!isHomePage) return;

    if (document.querySelector('.theme-toggle')) return;
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'theme-toggle';
    button.setAttribute('aria-label', 'Switch between light and dark mode');
    const updateLabel = () => {
      const light = root.classList.contains('light');
      button.innerHTML = light ? '☀️ <span>Light</span>' : '🌙 <span>Dark</span>';
      button.title = light ? 'Switch to dark mode' : 'Switch to light mode';
    };
    button.addEventListener('click', () => {
      root.classList.toggle('light');
      localStorage.setItem('campusone-theme', root.classList.contains('light') ? 'light' : 'dark');
      updateLabel();
    });
    updateLabel();
    const nav = document.querySelector('header nav, .site-header nav');
    if (nav) nav.appendChild(button);
    else document.body.appendChild(button);
    if (!nav) {
      button.style.position = 'fixed';
      button.style.right = '20px';
      button.style.bottom = '20px';
      button.style.zIndex = '100';
      button.style.boxShadow = '0 8px 24px #0003';
    }
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', addThemeButton);
  else addThemeButton();
})();

// Scroll reveal effects for cards and sections.
(function () {
  function initReveal() {
    const items = document.querySelectorAll('.service, .card, .product-card, .stat, .portal-card, .feature, .panel');
    if (!('IntersectionObserver' in window)) {
      items.forEach(el => el.classList.add('revealed'));
      return;
    }
    const observer = new IntersectionObserver((entries, obs) => {
      entries.forEach(entry => {
        if (!entry.isIntersecting) return;
        entry.target.classList.add('revealed');
        obs.unobserve(entry.target);
      });
    }, { threshold: 0.12 });
    items.forEach((el, index) => {
      el.classList.add('reveal-ready');
      el.style.transitionDelay = `${Math.min(index * 45, 360)}ms`;
      observer.observe(el);
    });
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', initReveal);
  else initReveal();
})();

// Replace basic emoji glyphs with consistent, professional inline SVG icons.
(function () {
  const icons = {
    '🎓': '<path d="M2 8 12 3l10 5-10 5L2 8Z"/><path d="M6 11v5c3 2 9 2 12 0v-5M22 9v6"/>',
    '🛡️': '<path d="M12 3 20 6v6c0 5-3.5 8-8 10-4.5-2-8-5-8-10V6l8-3Z"/><path d="m8 12 3 3 5-6"/>',
    '📝': '<path d="M5 3h10l4 4v14H5z"/><path d="M15 3v5h4M8 12h8M8 16h6"/>',
    '🖨️': '<path d="M6 9V3h12v6M6 17H4a2 2 0 0 1-2-2v-4a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v4a2 2 0 0 1-2 2h-2"/><path d="M6 14h12v7H6zM17 12h1"/>',
    '🛒': '<path d="M3 4h2l2 12h11l3-9H6"/><circle cx="9" cy="20" r="1.5"/><circle cx="18" cy="20" r="1.5"/>',
    '🍱': '<path d="M4 7h16v13H4zM4 12h16M12 7V4M8 4h8"/>',
    '🍽️': '<path d="M7 3v8M4 3v5a3 3 0 0 0 6 0V3M7 11v10M16 3v18M16 3c5 2 5 7 0 9"/>',
    '📦': '<path d="m3 7 9-4 9 4-9 4-9-4Z"/><path d="M3 7v10l9 4 9-4V7M12 11v10"/>',
    '🔍': '<circle cx="10.5" cy="10.5" r="6.5"/><path d="m16 16 5 5"/>',
    '⚠️': '<path d="m12 3 10 18H2L12 3Z"/><path d="M12 9v5M12 18h.01"/>',
    '☀️': '<circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2M2 12h2M20 12h2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M19.1 4.9l-1.4 1.4M6.3 17.7l-1.4 1.4"/>',
    '🌙': '<path d="M20 15.5A8.5 8.5 0 0 1 8.5 4 8.5 8.5 0 1 0 20 15.5Z"/>',
    '＋': '<path d="M12 5v14M5 12h14"/>',
    '✓': '<path d="m5 12 4 4L19 6"/>',
  };
  const keys = Object.keys(icons).sort((a,b) => b.length - a.length);
  const pattern = new RegExp('(' + keys.map(k => k.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).join('|') + ')', 'gu');

  function iconNode(key) {
    const span = document.createElement('span');
    span.className = 'ui-icon';
    span.setAttribute('aria-hidden', 'true');
    span.innerHTML = '<svg viewBox="0 0 24 24" focusable="false">' + icons[key] + '</svg>';
    return span;
  }

  function replaceTextNode(node) {
    if (!pattern.test(node.nodeValue)) { pattern.lastIndex = 0; return; }
    pattern.lastIndex = 0;
    const fragment = document.createDocumentFragment();
    let last = 0;
    node.nodeValue.replace(pattern, (match, _group, offset) => {
      if (offset > last) fragment.appendChild(document.createTextNode(node.nodeValue.slice(last, offset)));
      fragment.appendChild(iconNode(match));
      last = offset + match.length;
      return match;
    });
    if (last < node.nodeValue.length) fragment.appendChild(document.createTextNode(node.nodeValue.slice(last)));
    node.parentNode.replaceChild(fragment, node);
  }

  function replaceIcons() {
    const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, {
      acceptNode(node) {
        const parent = node.parentElement;
        if (!parent || ['SCRIPT','STYLE','TEXTAREA','INPUT'].includes(parent.tagName) || parent.closest('.ui-icon')) return NodeFilter.FILTER_REJECT;
        return NodeFilter.FILTER_ACCEPT;
      }
    });
    const nodes = [];
    let node;
    while ((node = walker.nextNode())) nodes.push(node);
    nodes.forEach(replaceTextNode);
  }

  function refreshThemeIcon() {
    const button = document.querySelector('.theme-toggle');
    if (!button) return;
    const light = document.documentElement.classList.contains('light');
    const key = light ? '☀️' : '🌙';
    button.innerHTML = '';
    button.appendChild(iconNode(key));
    const label = document.createElement('span');
    label.textContent = light ? 'Light' : 'Dark';
    button.appendChild(label);
    button.title = light ? 'Switch to dark mode' : 'Switch to light mode';
  }

  function initProfessionalIcons() {
    replaceIcons();
    refreshThemeIcon();
    const button = document.querySelector('.theme-toggle');
    if (button && !button.dataset.iconRefreshBound) {
      button.dataset.iconRefreshBound = '1';
      button.addEventListener('click', () => setTimeout(refreshThemeIcon, 0));
    }
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', initProfessionalIcons);
  else initProfessionalIcons();
})();

// Upload verification previews: let students confirm the selected file before submitting.
(function () {
  function initUploadPreviews() {
    document.querySelectorAll('[data-upload-preview]').forEach(preview => {
      const input = document.getElementById(preview.dataset.uploadPreview);
      if (!input || input.dataset.previewBound) return;
      input.dataset.previewBound = '1';
      input.addEventListener('change', () => {
        preview.replaceChildren();
        const file = input.files && input.files[0];
        if (!file) { preview.hidden = true; return; }
        preview.hidden = false;
        const meta = document.createElement('div');
        meta.className = 'preview-meta';
        meta.textContent = `Selected file: ${file.name} (${(file.size / 1024).toFixed(1)} KB)`;
        preview.appendChild(meta);
        const url = URL.createObjectURL(file);
        if (file.type.startsWith('image/')) {
          const image = document.createElement('img');
          image.src = url;
          image.alt = 'Preview of selected upload';
          preview.appendChild(image);
        } else if (file.type === 'application/pdf' || file.name.toLowerCase().endsWith('.pdf')) {
          const frame = document.createElement('iframe');
          frame.src = url;
          frame.title = 'Preview of selected PDF';
          preview.appendChild(frame);
        } else {
          const note = document.createElement('p');
          note.className = 'preview-note';
          note.textContent = 'This file type cannot be previewed in the browser. Confirm the filename before submitting.';
          preview.appendChild(note);
        }
        const note = document.createElement('p');
        note.className = 'preview-note';
        note.textContent = 'Please verify that this is the correct file before submitting.';
        preview.appendChild(note);
      });
    });
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', initUploadPreviews);
  else initUploadPreviews();
})();

// Premium UI Micro-interactions: Button Ripple & Magnetic Card Accent
(function () {
  function initRipples() {
    document.querySelectorAll('.btn, .cart-pill').forEach(button => {
      if (button.dataset.rippleBound) return;
      button.dataset.rippleBound = '1';
      button.addEventListener('click', function (e) {
        const rect = button.getBoundingClientRect();
        const ripple = document.createElement('span');
        ripple.className = 'btn-ripple';
        const diameter = Math.max(rect.width, rect.height);
        ripple.style.width = ripple.style.height = `${diameter}px`;
        ripple.style.left = `${e.clientX - rect.left - diameter / 2}px`;
        ripple.style.top = `${e.clientY - rect.top - diameter / 2}px`;
        button.appendChild(ripple);
        setTimeout(() => ripple.remove(), 600);
      });
    });
  }

  function initCardTilt() {
    const cards = document.querySelectorAll('.service, .portal-card, .stat, .product-card, .card');
    cards.forEach(card => {
      if (card.dataset.tiltBound) return;
      card.dataset.tiltBound = '1';
      card.addEventListener('mousemove', e => {
        const rect = card.getBoundingClientRect();
        const x = e.clientX - rect.left;
        const y = e.clientY - rect.top;
        const centerX = rect.width / 2;
        const centerY = rect.height / 2;
        const rotateX = ((y - centerY) / centerY) * -5;
        const rotateY = ((x - centerX) / centerX) * 5;
        card.style.transform = `perspective(1000px) translateY(-8px) rotateX(${rotateX.toFixed(2)}deg) rotateY(${rotateY.toFixed(2)}deg) scale(1.02)`;
      });
      card.addEventListener('mouseleave', () => {
        card.style.transform = '';
      });
    });
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', () => { initRipples(); initCardTilt(); });
  } else {
    initRipples(); initCardTilt();
  }
})();

// Interactive Constellation Background for Every Single Page in CampusOne
(function () {
  function initConstellation() {
    let canvas = document.getElementById('constellation-canvas');

    if (!canvas) {
      canvas = document.createElement('canvas');
      canvas.id = 'constellation-canvas';
      document.body.insertBefore(canvas, document.body.firstChild);
    }
    if (!canvas || canvas.dataset.active) return;
    canvas.dataset.active = '1';

    const ctx = canvas.getContext('2d');
    let width = canvas.width = window.innerWidth;
    let height = canvas.height = window.innerHeight;

    window.addEventListener('resize', () => {
      width = canvas.width = window.innerWidth;
      height = canvas.height = window.innerHeight;
    });

    const numParticles = Math.min(110, Math.floor(width * height / 12000));
    const particles = [];
    const maxDistance = 145;
    let mouse = { x: -1000, y: -1000, radius: 170 };

    window.addEventListener('mousemove', (e) => {
      mouse.x = e.clientX;
      mouse.y = e.clientY;
    });

    window.addEventListener('mouseleave', () => {
      mouse.x = -1000;
      mouse.y = -1000;
    });

    for (let i = 0; i < numParticles; i++) {
      particles.push({
        x: Math.random() * width,
        y: Math.random() * height,
        vx: (Math.random() - 0.5) * 0.75,
        vy: (Math.random() - 0.5) * 0.75,
        radius: Math.random() * 2.2 + 1.2,
        color: Math.random() < 0.5 ? 'rgba(99, 102, 241, ' : 'rgba(56, 189, 248, '
      });
    }

    function animate() {
      ctx.clearRect(0, 0, width, height);

      for (let i = 0; i < particles.length; i++) {
        const p = particles[i];
        p.x += p.vx;
        p.y += p.vy;

        if (p.x < 0 || p.x > width) p.vx *= -1;
        if (p.y < 0 || p.y > height) p.vy *= -1;

        const dx = mouse.x - p.x;
        const dy = mouse.y - p.y;
        const dist = Math.sqrt(dx * dx + dy * dy);

        if (dist < mouse.radius) {
          const angle = Math.atan2(dy, dx);
          const force = (mouse.radius - dist) / mouse.radius;
          p.x -= Math.cos(angle) * force * 1.6;
          p.y -= Math.sin(angle) * force * 1.6;
        }

        ctx.beginPath();
        ctx.arc(p.x, p.y, p.radius, 0, Math.PI * 2);
        ctx.fillStyle = p.color + '0.75)';
        ctx.fill();

        for (let j = i + 1; j < particles.length; j++) {
          const p2 = particles[j];
          const ldx = p.x - p2.x;
          const ldy = p.y - p2.y;
          const ldist = Math.sqrt(ldx * ldx + ldy * ldy);

          if (ldist < maxDistance) {
            const alpha = (1 - ldist / maxDistance) * 0.38;
            ctx.beginPath();
            ctx.moveTo(p.x, p.y);
            ctx.lineTo(p2.x, p2.y);
            let strokeGrad = ctx.createLinearGradient(p.x, p.y, p2.x, p2.y);
            strokeGrad.addColorStop(0, p.color + alpha + ')');
            strokeGrad.addColorStop(1, p2.color + alpha + ')');
            ctx.strokeStyle = strokeGrad;
            ctx.lineWidth = 1.1;
            ctx.stroke();
          }
        }
      }
      requestAnimationFrame(animate);
    }

    animate();
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', initConstellation);
  } else {
    initConstellation();
  }
})();


