// Add scrolled class to nav for optional styling
const nav = document.querySelector('nav.site-nav');
if (nav) {
  window.addEventListener('scroll', () => {
    nav.classList.toggle('scrolled', window.scrollY > 40);
  }, { passive: true });
}
