jQuery(document).ready(function ($) {
  initOldVersionWarnings($);
});

function initOldVersionWarnings($) {
  $.get(versionsJsonUri(), function (akkaVersionsData) {
    var thisVersion = browsedAkkaVersion();
    if (thisVersion.includes("-SNAPSHOT")) {
      console.log("Detected SNAPSHOT Akka version...");
      // we could show a 'hey, this is a snapshot' notice
    } else {
      for (var series in akkaVersionsData) {
        if (thisVersion.startsWith(series)) {
          showVersionWarning(thisVersion, akkaVersionsData, akkaVersionsData[series]);
        }
      }
    }
  });
}

function insteadUrl(version, instead) {
  return ("" + window.location).replace(version, instead);
}

function showVersionWarning(version, akkaVersionsData, seriesInfo) {
  console.log("Akka version:", version);
  var targetUrl = ("" + window.location).replace(version, seriesInfo.latest);
  var $floatyWarning = $('<div id="floaty-warning"/>');

  console.log("Current version info", seriesInfo);

  var isOutdated = !!seriesInfo.outdated;
  var isLatestInSeries = version != seriesInfo.latest;

  if (isOutdated) {
    $floatyWarning.addClass("warning");
    var instead = akkaVersionsData[seriesInfo.instead].latest;

    console.log("Akka " + version + " is outdated. Suggesting 'latest' of ", seriesInfo.instead, "by 'instead' key.");
    var unsupportedMessage = '<p>' +
        '<span style="font-weight: bold">This version of Akka (' + version + ') has been end-of-lifed and is currently not supported! </span><br/>' +
        'Please upgrade to <a href="' + insteadUrl(version, instead) + '">Akka ' + instead + '</a> or <a href="http://www.typesafe.com/products/typesafe-reactive-platform">Typesafe Reactive Platform</a> as soon as possible.' +
        '</p>';
    $floatyWarning.append(unsupportedMessage);
  }

  if (isLatestInSeries) {
    $floatyWarning
        .append('<p>' +
            'You are browsing the docs for Akka ' + version + ', ' +
            'however the latest release in this series is: ' +
            '<a href="' + targetUrl + '">' + seriesInfo.latest + '</a>. <br/>' +
            '</p>');
  }

  // add bottom clicky link "to same page"
  if (isOutdated) {
    $floatyWarning.append(
        '<p>' +
        '<a href="' + insteadUrl(version, instead) + '">Click here to go to the same page on the ' + akkaVersionsData[seriesInfo.instead].latest + ' version of the docs.</a>' +
        '</p>')
  } else if (isLatestInSeries && isOutdated) {
    $floatyWarning.append(
        '<p>' +
        '<a href="' + targetUrl + '">Click here to go to the same page on the ' + seriesInfo.latest + ' version of the docs.</a>' +
        '</p>')
  }

  $floatyWarning
      .hide()
      .prependTo("body")
      .show()
      .delay(10 * 1000)
      .fadeOut()
}

// e.g. "docs/akka/2.3.10/scala/persistence.html" => "2.3.10"
function browsedAkkaVersion() {
  var globalSetting = window.DOCUMENTATION_OPTIONS.VERSION; // generated by Sphinx
  if (globalSetting) {
    return globalSetting;
  } else {
    var path = window.location.pathname;
    var path2 = path.substring(path.indexOf("/", "/docs/akka".length) + 1);
    var version = path2.substring(0, path2.indexOf("/"));
    console.log("Detected version of docs using window.location parsing:", version);
    return version;
  }
}

function versionsJsonUri() {
  if (window.location.pathname.includes("stream-and-http"))
    return "http://doc.akka.io/docs/akka-stream-and-http-experimental/versions.json"
  else
    return "http://doc.akka.io/docs/akka/versions.json";
}
