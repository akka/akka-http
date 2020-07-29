var sendEdit = enableAutoReloader();

// allow using Ctrl+E to navigate to file in IDEA
document.onkeydown = function(event) {
  if (event.key === "e" && event.ctrlKey) {
      console.log("test");
      sendEdit(document.location.pathname);
      console.log("sent!");
  }
};

window.addEventListener('load', () => {
  const headings = document.querySelectorAll('a.anchor');

  document.addEventListener('scroll', (e) => {
    headings.forEach(ha => {
      const rect = ha.getBoundingClientRect();
      if(rect.top > 0 && rect.top < 150) {
        const location = window.location.toString().split('#')[0];
        history.replaceState(null, null, location + '#' + ha.name);
      }
    });
  });
});