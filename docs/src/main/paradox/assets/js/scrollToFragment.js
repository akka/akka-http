$(document).ready(function() {
  if(window.location.hash.length > 0) {
    window.scrollTo(0, $(window.location.hash).offset().top);
  }
});