/**
 * KCP — Knowledge Context Protocol
 * Site interactivity: dark mode, tabs, copy-to-clipboard, FAQ, scroll animations
 */

(function () {
  'use strict';

  // ──────────────────────────────────────────────
  // Dark Mode Toggle
  // ──────────────────────────────────────────────

  const themeToggle = document.getElementById('theme-toggle');
  const htmlEl = document.documentElement;
  const iconSun = themeToggle.querySelector('.icon-sun');
  const iconMoon = themeToggle.querySelector('.icon-moon');
  const hljsLight = document.getElementById('hljs-light');
  const hljsDark = document.getElementById('hljs-dark');

  function getPreferredTheme() {
    const stored = localStorage.getItem('kcp-theme');
    if (stored) return stored;
    return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
  }

  function applyTheme(theme) {
    htmlEl.setAttribute('data-theme', theme);
    localStorage.setItem('kcp-theme', theme);

    if (theme === 'dark') {
      iconSun.style.display = 'none';
      iconMoon.style.display = 'block';
      hljsLight.disabled = true;
      hljsDark.disabled = false;
    } else {
      iconSun.style.display = 'block';
      iconMoon.style.display = 'none';
      hljsLight.disabled = false;
      hljsDark.disabled = true;
    }

    // Re-highlight all code blocks after theme switch
    document.querySelectorAll('pre code').forEach(function (block) {
      hljs.highlightElement(block);
    });
  }

  applyTheme(getPreferredTheme());

  themeToggle.addEventListener('click', function () {
    var current = htmlEl.getAttribute('data-theme');
    applyTheme(current === 'dark' ? 'light' : 'dark');
  });

  // Listen for system theme changes
  window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', function (e) {
    if (!localStorage.getItem('kcp-theme')) {
      applyTheme(e.matches ? 'dark' : 'light');
    }
  });


  // ──────────────────────────────────────────────
  // Mobile Navigation
  // ──────────────────────────────────────────────

  var mobileToggle = document.getElementById('mobile-toggle');
  var navLinks = document.getElementById('nav-links');

  mobileToggle.addEventListener('click', function () {
    navLinks.classList.toggle('open');
  });

  // Close mobile nav when a link is clicked
  navLinks.querySelectorAll('a').forEach(function (link) {
    link.addEventListener('click', function () {
      navLinks.classList.remove('open');
    });
  });


  // ──────────────────────────────────────────────
  // Syntax Highlighting
  // ──────────────────────────────────────────────

  hljs.highlightAll();


  // ──────────────────────────────────────────────
  // Tabs (Examples section)
  // ──────────────────────────────────────────────

  var tabBtns = document.querySelectorAll('.tab-btn');
  var tabPanels = document.querySelectorAll('.tab-panel');

  tabBtns.forEach(function (btn) {
    btn.addEventListener('click', function () {
      var target = btn.getAttribute('data-tab');

      // Deactivate all
      tabBtns.forEach(function (b) {
        b.classList.remove('active');
        b.setAttribute('aria-selected', 'false');
      });
      tabPanels.forEach(function (p) {
        p.classList.remove('active');
      });

      // Activate target
      btn.classList.add('active');
      btn.setAttribute('aria-selected', 'true');
      document.getElementById('tab-' + target).classList.add('active');

      // Re-highlight code in newly visible panel
      var panel = document.getElementById('tab-' + target);
      panel.querySelectorAll('pre code').forEach(function (block) {
        if (!block.dataset.highlighted) {
          hljs.highlightElement(block);
        }
      });
    });
  });


  // ──────────────────────────────────────────────
  // Copy to Clipboard
  // ──────────────────────────────────────────────

  document.querySelectorAll('.copy-btn').forEach(function (btn) {
    btn.addEventListener('click', function () {
      var targetId = btn.getAttribute('data-copy');
      var codeEl = document.getElementById(targetId);

      if (!codeEl) return;

      var text = codeEl.textContent;

      navigator.clipboard.writeText(text).then(function () {
        var label = btn.querySelector('span');
        var originalText = label.textContent;
        label.textContent = 'Copied!';
        btn.classList.add('copied');

        setTimeout(function () {
          label.textContent = originalText;
          btn.classList.remove('copied');
        }, 2000);
      }).catch(function () {
        // Fallback for older browsers
        var textarea = document.createElement('textarea');
        textarea.value = text;
        textarea.style.position = 'fixed';
        textarea.style.opacity = '0';
        document.body.appendChild(textarea);
        textarea.select();
        try {
          document.execCommand('copy');
          var label = btn.querySelector('span');
          label.textContent = 'Copied!';
          btn.classList.add('copied');
          setTimeout(function () {
            label.textContent = 'Copy';
            btn.classList.remove('copied');
          }, 2000);
        } catch (e) {
          // Silent fail
        }
        document.body.removeChild(textarea);
      });
    });
  });


  // ──────────────────────────────────────────────
  // FAQ Accordion
  // ──────────────────────────────────────────────

  document.querySelectorAll('.faq-question').forEach(function (btn) {
    btn.addEventListener('click', function () {
      var item = btn.closest('.faq-item');
      var isOpen = item.classList.contains('open');

      // Close all FAQ items
      document.querySelectorAll('.faq-item').forEach(function (faq) {
        faq.classList.remove('open');
        faq.querySelector('.faq-question').setAttribute('aria-expanded', 'false');
      });

      // Open clicked one (if it was closed)
      if (!isOpen) {
        item.classList.add('open');
        btn.setAttribute('aria-expanded', 'true');
      }
    });
  });


  // ──────────────────────────────────────────────
  // Scroll-based Reveal Animations
  // ──────────────────────────────────────────────

  var revealElements = document.querySelectorAll('.reveal');

  if ('IntersectionObserver' in window) {
    var observer = new IntersectionObserver(function (entries) {
      entries.forEach(function (entry) {
        if (entry.isIntersecting) {
          entry.target.classList.add('visible');
          observer.unobserve(entry.target);
        }
      });
    }, {
      threshold: 0.1,
      rootMargin: '0px 0px -40px 0px'
    });

    revealElements.forEach(function (el) {
      observer.observe(el);
    });
  } else {
    // Fallback: show everything immediately
    revealElements.forEach(function (el) {
      el.classList.add('visible');
    });
  }


  // ──────────────────────────────────────────────
  // Active Nav Link Tracking
  // ──────────────────────────────────────────────

  var sections = document.querySelectorAll('section[id]');
  var navAnchors = document.querySelectorAll('.nav__links a');

  if ('IntersectionObserver' in window && sections.length) {
    var sectionObserver = new IntersectionObserver(function (entries) {
      entries.forEach(function (entry) {
        if (entry.isIntersecting) {
          var id = entry.target.getAttribute('id');
          navAnchors.forEach(function (a) {
            if (a.getAttribute('href') === '#' + id) {
              a.style.color = 'var(--text-primary)';
            } else {
              a.style.color = '';
            }
          });
        }
      });
    }, {
      threshold: 0.2,
      rootMargin: '-80px 0px -50% 0px'
    });

    sections.forEach(function (section) {
      sectionObserver.observe(section);
    });
  }

})();
