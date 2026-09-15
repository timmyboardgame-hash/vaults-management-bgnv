/*
 * Vault Analytics — วาดกราฟ SVG (ย้ายมาจาก mock) · ไม่ใช้ chart library
 *
 * ตัวเลข ตาราง heatmap และแท่งแนวนอน render ฝั่ง server หมดแล้ว
 * ไฟล์นี้วาดแค่ 2 อย่างที่ต้องคำนวณพิกัด: กราฟแนวโน้ม กับ sparkline
 *   กราฟแนวโน้ม → window.VA_TREND = [{label, bookings, eventBookings, cycles}]
 *   sparkline    → element ที่มี data-spark="1,2,3" (+ data-color)
 */
(function () {
  "use strict";

  var COLORS = {
    cycles: "#a78bfa",
    bookings: "#38bdf8",
    event: "#fbbf24",
    grid: "rgba(148,163,184,.14)",
    label: "#64748b"
  };

  function nf(n) { return Math.round(n).toLocaleString("en-US"); }

  function svgEl(w, h, body, label) {
    return '<svg viewBox="0 0 ' + w + " " + h + '" role="img" aria-label="' + label + '">' + body + "</svg>";
  }

  /* กราฟเส้นหลายชุด — ชุดแรกมีพื้นที่ใต้เส้น · สเกลแกน y ปัดเป็นเลขกลม */
  function lineChart(el, series, labels) {
    var w = 780, h = 240, pad = { t: 12, r: 36, b: 26, l: 46 };
    var iw = w - pad.l - pad.r, ih = h - pad.t - pad.b;
    var all = [];
    series.forEach(function (s) { all = all.concat(s.pts); });
    var max = Math.max.apply(null, all.concat([1]));
    var step = Math.pow(10, Math.floor(Math.log10(Math.max(max / 4, 1))));
    var top = Math.max(4, Math.ceil(max / (step * 2)) * step * 2);
    var n = labels.length;
    var X = function (i) { return n === 1 ? pad.l + iw / 2 : pad.l + (iw * i) / (n - 1); };
    var Y = function (v) { return pad.t + ih - (ih * v) / top; };

    var g = "";
    for (var i = 0; i <= 4; i++) {
      var v = (top / 4) * i, y = Y(v).toFixed(1);
      g += '<line x1="' + pad.l + '" y1="' + y + '" x2="' + (w - pad.r) + '" y2="' + y + '" stroke="' + COLORS.grid + '"/>';
      g += '<text x="' + (pad.l - 8) + '" y="' + (Y(v) + 3.5).toFixed(1) + '" text-anchor="end" font-size="10" fill="' + COLORS.label + '">' + nf(v) + "</text>";
    }

    var every = Math.max(1, Math.ceil(n / 7)), xs = "";
    labels.forEach(function (lb, i) {
      if (i % every && i !== n - 1) return;
      var anchor = i === n - 1 && n > 1 ? "end" : "middle";
      xs += '<text x="' + X(i).toFixed(1) + '" y="' + (h - 8) + '" text-anchor="' + anchor + '" font-size="10" fill="' + COLORS.label + '">' + lb + "</text>";
    });

    var body = "";
    series.forEach(function (s, si) {
      var p = s.pts.map(function (v, i) { return X(i).toFixed(1) + "," + Y(v).toFixed(1); }).join(" ");
      if (si === 0 && n > 1) {
        body += '<polygon points="' + pad.l + "," + (pad.t + ih) + " " + p + " " + (w - pad.r) + "," + (pad.t + ih) +
                '" fill="' + s.color + '" fill-opacity=".12" stroke="none"/>';
      }
      body += '<polyline points="' + p + '" fill="none" stroke="' + s.color + '" stroke-width="' + (s.width || 2) +
              '" stroke-linejoin="round" stroke-linecap="round"/>';
      var li = s.pts.length - 1;
      if (si === 0 && li >= 0) {
        body += '<circle cx="' + X(li).toFixed(1) + '" cy="' + Y(s.pts[li]).toFixed(1) + '" r="3.5" fill="' + s.color + '" stroke="#13162a" stroke-width="2"/>';
      }
    });

    el.innerHTML = svgEl(w, h, g + xs + body, "กราฟปริมาณการใช้งาน");
  }

  /* sparkline — สเกลกับตัวเอง บอกได้แค่รูปทรง ไม่ใช่ขนาด */
  function spark(el) {
    var pts = (el.getAttribute("data-spark") || "").split(",").filter(Boolean).map(Number);
    if (pts.length < 2) return;
    var color = el.getAttribute("data-color") || COLORS.cycles;
    var w = 120, h = 26;
    var mx = Math.max.apply(null, pts), mn = Math.min.apply(null, pts), rg = mx - mn || 1;
    var X = function (i) { return (w * i) / (pts.length - 1); };
    var Y = function (v) { return h - 3 - ((h - 6) * (v - mn)) / rg; };
    var p = pts.map(function (v, i) { return X(i).toFixed(1) + "," + Y(v).toFixed(1); }).join(" ");
    el.innerHTML =
      '<svg viewBox="0 0 ' + w + " " + h + '" preserveAspectRatio="none" aria-hidden="true">' +
      '<polygon points="0,' + h + " " + p + " " + w + "," + h + '" fill="' + color + '" fill-opacity=".14"/>' +
      '<polyline points="' + p + '" fill="none" stroke="' + color + '" stroke-width="1.6" stroke-linejoin="round"/>' +
      '<circle cx="' + X(pts.length - 1).toFixed(1) + '" cy="' + Y(pts[pts.length - 1]).toFixed(1) + '" r="2" fill="' + color + '"/>' +
      "</svg>";
  }

  function init() {
    var trend = window.VA_TREND || [];
    var el = document.getElementById("vaTrend");
    if (el && trend.length) {
      lineChart(el, [
        { pts: trend.map(function (p) { return p.cycles; }), color: COLORS.cycles },
        { pts: trend.map(function (p) { return p.bookings; }), color: COLORS.bookings, width: 1.7 },
        { pts: trend.map(function (p) { return p.eventBookings; }), color: COLORS.event, width: 1.5 }
      ], trend.map(function (p) { return p.label; }));
    }

    /* แท็บความผิดปกติ — ชุดแรก (anomaly รวม) มีพื้นที่ใต้เส้น */
    var problems = window.VA_PROBLEM_TREND || [];
    var pel = document.getElementById("vaProblemTrend");
    if (pel && problems.length) {
      lineChart(pel, [
        { pts: problems.map(function (p) { return p.anomalies; }), color: "#fb923c" },
        { pts: problems.map(function (p) { return p.noAck; }), color: "#f87171", width: 1.7 },
        { pts: problems.map(function (p) { return p.unpaired; }), color: "#fbbf24", width: 1.7 },
        { pts: problems.map(function (p) { return p.lateEvents; }), color: "#38bdf8", width: 1.5 }
      ], problems.map(function (p) { return p.label; }));
    }

    document.querySelectorAll("[data-spark]").forEach(spark);
  }

  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", init);
  else init();
})();
