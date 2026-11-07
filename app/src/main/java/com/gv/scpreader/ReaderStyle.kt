package com.gv.scpreader

import org.json.JSONObject

data class Palette(
    val name: String,
    val bg: String,
    val surface: String,
    val surfaceHi: String,
    val text: String,
    val muted: String,
    val link: String,
    val accent: String,
    val border: String,
    val imageFilter: String,
)

/** Builds the CSS/JS that turns a wikidot page into a clean night-mode reader. */
object ReaderStyle {
    val PALETTES = listOf(
        Palette("Dim", "#121214", "#1B1B1F", "#26262B", "#D4D4D4", "#8C8C94", "#8AB4F8", "#E57368", "#2E2E35",
            "brightness(.82) contrast(1.05)"),
        Palette("AMOLED", "#000000", "#0C0C0C", "#181818", "#BDBDBD", "#737373", "#7FA7E0", "#D05A4F", "#202020",
            "brightness(.7) contrast(1.05)"),
        Palette("Sepia night", "#17130E", "#221C14", "#2E261B", "#D8C7A3", "#9A8A6C", "#E2A35B", "#D9874A", "#3A3022",
            "brightness(.75) sepia(.35)"),
        // Pure red on black preserves dark adaptation — reading in bed with the lights off.
        Palette("Red light", "#000000", "#0A0000", "#160303", "#D23C3C", "#842626", "#FF6B5B", "#FF4433", "#3A0D0D",
            "grayscale(1) brightness(.55) sepia(1) hue-rotate(-50deg) saturate(5)"),
        // Foundation terminal.
        Palette("Terminal", "#020A04", "#06140A", "#0B2012", "#4FE07A", "#2E8A4A", "#9CFFB8", "#7DFF9E", "#123A1E",
            "grayscale(1) brightness(.6) sepia(1) hue-rotate(70deg) saturate(3)"),
    )

    val FONTS = listOf(
        "Sans" to "system-ui, Roboto, 'Noto Sans', sans-serif",
        "Serif" to "'Noto Serif', 'Droid Serif', Georgia, serif",
        "Mono" to "'Droid Sans Mono', 'Cutive Mono', monospace",
    )

    fun palette(p: Prefs) = PALETTES.getOrElse(p.theme) { PALETTES[0] }

    fun css(p: Prefs) = css(p.theme, p.font, p.lineHeight, p.dimImages, p.strictDark, p.textZoom)

    /**
     * [zoomPercent] scales the article like page zoom (text, spacing and boxes together).
     * WebView's own textZoom only scales fonts, so pages with pixel-sized line heights or
     * boxes (e.g. the SCP classification headers) end up with overlapping text.
     */
    fun css(
        theme: Int, fontIndex: Int, lineHeight: Float, dimImages: Boolean, strictDark: Boolean,
        zoomPercent: Int = 100,
    ): String {
        val c = PALETTES.getOrElse(theme) { PALETTES[0] }
        val font = FONTS.getOrElse(fontIndex) { FONTS[0] }.second
        val lh = "%.2f".format(java.util.Locale.US, lineHeight)
        val img = if (dimImages) "filter:${c.imageFilter}!important;" else ""
        val pc = "html body #page-content"
        val tbl = "html body #main-content #page-content .gv-table-scroll table"
        val fit = "html body #main-content #page-content .gv-table-scroll.gv-fit table"
        val strict = if (!strictDark) "" else """
            #page-content *:not(a):not(img){color:${c.text}!important;background-color:transparent!important;
              border-color:${c.border}!important;text-shadow:none!important;box-shadow:none!important}
            #page-content [style*="background-image"]{background-image:none!important}
        """
        return """
            html,body{background:${c.bg}!important;color:${c.text}!important}
            body{margin:0!important;padding:0!important;min-width:0!important}
            #header,#top-bar,.mobile-top-bar,#side-bar,#footer,#footer-bar,#license-area,.licensebox,
            #page-options-container,#page-options-bottom,#page-options-bottom-tips,#page-options-area-bottom,
            #page-info,#page-info-break,#action-area-top,.page-rate-widget-box,.rate-box-with-credit-button,
            .creditRate,.credit-rate,#login-status,#search-top-box,#consent-box,.scpnet-interwiki-wrapper,
            .ios-cache-issue-notification,.wd-adunit,[id^="nitropay"],[class*="nitro"],
            iframe[src*="nitropay"],#ncmp__tool,#back-to-top-button,.mobile-only-banner{display:none!important}
            #container-wrap-wrap,#container-wrap,#container,#content-wrap,#main-content,#skrollr-body{
              width:auto!important;max-width:none!important;min-width:0!important;margin:0!important;padding:0!important;
              float:none!important;background:${c.bg}!important;border:0!important;box-shadow:none!important;
              position:static!important}
            #main-content{padding:14px 18px 40px!important}
            #main-content>*{zoom:${zoomPercent / 100.0}}
            /* The classification header is a fixed design: keep it at its natural size and wrapping. */
            html body #page-content .anom-bar-container,html body #page-content .gv-fixed-design{
              zoom:${"%.4f".format(java.util.Locale.US, 100.0 / zoomPercent)}}
            html body #page-content .gv-fixed-design .gv-fixed-design{zoom:1}
            html body #page-content .anom-bar-container,html body #page-content .anom-bar-container *{
              overflow-wrap:normal!important;word-break:normal!important}
            html,body{overflow-x:hidden!important}
            #extra-div-1,#extra-div-2,#extra-div-3,#extra-div-4,#extra-div-5,#extra-div-6,
            #container-wrap::before,#container-wrap::after,#content-wrap::before,#content-wrap::after,
            #main-content::before,#main-content::after,#container::before,#container::after{display:none!important}
            #page-content *:not(table):not(thead):not(tbody):not(tr):not(td):not(th){max-width:100%!important;box-sizing:border-box}
            .page-tags a[href*="/tag/_"]{display:none!important}
            $strict
            #page-title{color:${c.accent}!important;font-family:$font!important;font-size:1.65em!important;
              font-weight:600!important;border:0!important;margin:.2em 0 .7em!important;padding:0!important}
            $pc{font-family:$font!important;color:${c.text}!important;font-size:1em!important;line-height:$lh!important;
              word-wrap:break-word;overflow-wrap:anywhere}
            $pc p:not(.anom-bar-container *),$pc li{line-height:$lh!important}
            $pc h1,$pc h2,$pc h3,$pc h4,$pc h5,$pc h6{color:${c.accent}!important;font-family:$font!important;line-height:1.3!important}
            $pc a,$pc a:visited,$pc a span{color:${c.link}!important}
            $pc a.newpage{color:${c.muted}!important}
            $pc .danger-diamond a,$pc .danger-diamond a span{color:transparent!important}
            $pc img{max-width:100%!important;height:auto!important;$img}
            $pc .scp-image-block{float:none!important;margin:1em auto!important;max-width:100%!important;
              background:${c.surface}!important;border:1px solid ${c.border}!important;box-shadow:none!important}
            $pc .scp-image-block img{width:100%!important}
            $pc .scp-image-caption{width:auto!important;background:${c.surface}!important;color:${c.muted}!important;border:0!important}
            $pc blockquote,$pc .blockquote,$pc pre,$pc .code,$pc .warning-box{
              background:${c.surface}!important;border:1px solid ${c.border}!important;border-left:3px solid ${c.accent}!important;
              color:${c.text}!important;margin-left:0!important;margin-right:0!important;border-radius:4px}
            $pc pre,$pc .code,$pc .code *{white-space:pre-wrap!important;word-break:break-word!important;font-size:.9em}
            $pc table{border-collapse:collapse}
            $pc .gv-table-scroll{display:block;width:100%;overflow-x:auto;overflow-y:hidden;margin:1em 0;padding:0!important}
            $tbl{display:table!important;width:max-content!important;min-width:100%!important;max-width:none!important;
              margin:0!important;float:none!important;overflow-wrap:normal!important;word-break:normal!important}
            $tbl td,$tbl th{min-width:0!important;max-width:20em!important;padding:6px 10px!important;vertical-align:top;
              white-space:normal!important;overflow-wrap:break-word!important;word-break:normal!important}
            $tbl td *:not(img),$tbl th *:not(img){max-width:none!important}
            $fit{width:100%!important;table-layout:fixed!important}
            $fit td,$fit th{max-width:none!important}
            $fit td *:not(img),$fit th *:not(img){max-width:100%!important}
            $pc td,$pc th{border-color:${c.border}!important}
            $pc th{background:${c.surface}!important}
            $pc hr{border:0!important;border-top:1px solid ${c.border}!important;background:none!important;height:0!important}
            $pc .collapsible-block-link{color:${c.link}!important;font-weight:600}
            $pc .yui-navset .yui-nav{border-color:${c.border}!important}
            $pc .yui-navset .yui-nav li a,$pc .yui-navset .yui-nav li a em{background:${c.surface}!important;color:${c.muted}!important;border-color:${c.border}!important}
            $pc .yui-navset .yui-nav .selected a,$pc .yui-navset .yui-nav .selected a em{background:${c.surfaceHi}!important;color:${c.accent}!important}
            $pc .yui-navset .yui-content{background:transparent!important;border-color:${c.border}!important}
            $pc .footnotes-footer,$pc .footnote-footer{color:${c.muted}!important}
            .page-tags{margin-top:2em!important;border-top:1px solid ${c.border};padding-top:1em}
            .page-tags span a,.page-tags a{display:inline-block;padding:2px 9px;margin:3px 2px;border:1px solid ${c.border};
              border-radius:12px;color:${c.muted}!important;font-size:.78em;text-decoration:none!important;background:${c.surface}}
            .footer-wikiwalk-nav{text-align:center;color:${c.muted}!important;margin:1.5em 0}
            .footer-wikiwalk-nav a{color:${c.link}!important}
            .odialog-container,.owindow{background:${c.surface}!important;color:${c.text}!important}
            ::selection{background:${c.accent};color:${c.bg}}
            $pc .gv-redact{background:#000!important;color:#000!important;border-radius:2px;cursor:pointer}
            $pc .gv-redact.gv-shown{background:${c.surfaceHi}!important;color:${c.accent}!important}
            html body #main-content #gv-next{display:block;margin:2.5em 0 1em;padding:16px 18px;border:1px solid ${c.border}!important;
              border-radius:12px;background:${c.surface}!important;text-decoration:none!important;font-family:$font}
            html body #main-content #gv-next small{display:block;color:${c.muted}!important;font-size:.75em;letter-spacing:.08em;text-transform:uppercase}
            html body #main-content #gv-next b{display:block;color:${c.accent}!important;font-size:1.1em;margin-top:4px}
            html body #main-content #gv-next span{display:block;color:${c.text}!important;font-size:.95em}
        """.trimIndent().replace(Regex("\\s*\n\\s*"), "\n")
    }

    // Android WebView blocks http:// media on https:// pages, and older articles link images that way.
    private const val UPGRADE_MEDIA_JS = """
        [].forEach.call(document.querySelectorAll('img[src^="http:"],source[src^="http:"],video[src^="http:"],audio[src^="http:"]'), function(e){
          e.src = e.getAttribute('src').replace(/^http:/, 'https:');
        });
    """

    /**
     * Runs at document start (before the page paints) in every frame, so there's no white flash.
     * Exposes window.__gvApply(css) to restyle live when settings change.
     */
    /** [tapZonePercent] is the width of each page-turn tap area; 0 disables tap paging. */
    fun documentStartJs(css: String, tapZonePercent: Int = 0) = """
        (function(){
          var css = ${JSONObject.quote(css)};
          window.__gvTapZone = $tapZonePercent;
          // Tap paging: a plain tap near the left/right edge scrolls a screen back/forward.
          // Taps on anything interactive (links, collapsibles, tabs, redactions...) behave as usual.
          if (window === window.top && !window.__gvTapBound) {
            window.__gvTapBound = true;
            document.addEventListener('click', function(ev){
              var z = window.__gvTapZone;
              if (!z || ev.defaultPrevented || ev.button) return;
              if (window.getSelection && String(window.getSelection())) return;
              var t = ev.target;
              if (t.nodeType !== 1) t = t.parentElement;
              if (!t) return;
              // The full-width "Continue reading" card only opens from its middle; its edges turn pages.
              var hit = t.closest('a,button,input,select,textarea,label,summary,video,audio,iframe,[onclick],' +
                  '[role=button],[tabindex],.collapsible-block-link,.yui-nav,.gv-redact');
              var card = t.closest('#gv-next');
              if (hit && hit !== card) return;
              if (!card && getComputedStyle(t).cursor === 'pointer') return;
              var x = ev.clientX / window.innerWidth, edge = z / 100;
              var dir = x < edge ? -1 : (x > 1 - edge ? 1 : 0);
              if (!dir) return;
              ev.preventDefault();
              window.scrollBy({top: dir * window.innerHeight * 0.88, behavior: 'smooth'});
            });
          }
          function apply(){
            var s = document.getElementById('gv-reader-css');
            if (!s) { s = document.createElement('style'); s.id = 'gv-reader-css'; }
            s.textContent = css;
            // Re-append so we stay last in the cascade, after the page's own theme CSS.
            var parent = document.body || document.head || document.documentElement;
            if (parent && (s.parentNode !== parent || s.nextSibling)) parent.appendChild(s);
          }
          window.__gvApply = function(c){ css = c; apply(); };
          // Boxes with a fixed pixel height overflow onto what's below once text is bigger or the
          // column narrower. Let those grow (never below their original height).
          window.__gvRelax = function(){
            var pc = document.getElementById('page-content'); if (!pc) return;
            var grow = [];
            [].forEach.call(pc.querySelectorAll('div,p,span,section,blockquote'), function(e){
              if (e.closest('.anom-bar-container, .gv-table-scroll')) return;
              if (e.scrollHeight <= e.clientHeight + 4 || e.clientHeight === 0) return;
              if (getComputedStyle(e).overflowY !== 'visible') return;
              grow.push([e, e.getBoundingClientRect().height]);
            });
            grow.forEach(function(g){
              g[0].style.setProperty('min-height', g[1] + 'px', 'important');
              g[0].style.setProperty('height', 'auto', 'important');
            });
            // Small blocks that pin text with absolute positioning are designed graphics (title cards,
            // warning screens); they only work at their natural size, like the classification header.
            [].forEach.call(pc.querySelectorAll('*'), function(e){
              var pos = getComputedStyle(e).position;
              if ((pos !== 'absolute' && pos !== 'fixed') || !e.textContent.trim()) return;
              var box = e.offsetParent;
              if (!box || box === pc || !pc.contains(box) || box.tagName !== 'DIV') return;
              if (box.closest('.anom-bar-container, .gv-fixed-design')) return;
              if (box.textContent.length < 600) box.classList.add('gv-fixed-design');
            });
          };
          apply();
          function upgradeMedia(){ $UPGRADE_MEDIA_JS }
          document.addEventListener('DOMContentLoaded', function(){
            upgradeMedia();
            if (!document.querySelector('meta[name=viewport]') && document.head) {
              var m = document.createElement('meta'); m.name = 'viewport';
              m.content = 'width=device-width, initial-scale=1'; document.head.appendChild(m);
            }
            apply(); setTimeout(apply, 400);
          });
          window.addEventListener('load', function(){ upgradeMedia(); apply(); setTimeout(apply, 1200); });
        })();
    """.trimIndent()

    /**
     * Runs after the page loads. Marks hidden black-bar redactions as tap-to-reveal,
     * optionally expands collapsibles, adds the "next article" card, and returns page metadata.
     */
    fun afterLoadJs(autoExpand: Boolean, nextSlug: String?, nextLabel: String?, nextTitle: String?) = """
        (function(){
          $UPGRADE_MEDIA_JS
          var pc = document.getElementById('page-content');
          if (pc) {
            pc.querySelectorAll('span[style],div[style]').forEach(function(e){
              var st = (e.getAttribute('style')||'').replace(/\s/g,'').toLowerCase();
              if (/background(-color)?:(black|#000\b|#000000|rgb\(0,0,0\))/.test(st) && e.textContent.trim().length) {
                e.classList.add('gv-redact');
              }
            });
            // Tables scroll sideways inside their own box; the rest of the page keeps to screen width.
            pc.querySelectorAll('table').forEach(function(t){
              if (t.parentNode.closest('table, .gv-table-scroll')) return;
              var w = document.createElement('div'); w.className = 'gv-table-scroll';
              // One-column tables are layout boxes, not data: fit them to the screen instead.
              var cols = 0;
              [].forEach.call(t.rows, function(r){
                var n = 0;
                [].forEach.call(r.cells, function(c){ n += c.colSpan; });
                cols = Math.max(cols, n);
              });
              if (cols < 2) w.classList.add('gv-fit');
              t.parentNode.insertBefore(w, t); w.appendChild(t);
            });
            if (!window.__gvRedactBound) {
              window.__gvRedactBound = true;
              document.addEventListener('click', function(ev){
                var r = ev.target.closest && ev.target.closest('.gv-redact');
                if (r) { r.classList.toggle('gv-shown'); ev.preventDefault(); }
              }, true);
            }
            if ($autoExpand) {
              pc.querySelectorAll('.collapsible-block-folded').forEach(function(f){
                f.style.display = 'none';
                var u = f.parentNode.querySelector('.collapsible-block-unfolded');
                if (u) u.style.display = 'block';
              });
            }
            var old = document.getElementById('gv-next'); if (old) old.remove();
            var ns = ${JSONObject.quote(nextSlug ?: "")};
            if (ns) {
              var a = document.createElement('a'); a.id = 'gv-next'; a.className = 'gv-next'; a.href = '/' + ns;
              var sm = document.createElement('small'); sm.textContent = 'Continue reading';
              var b = document.createElement('b'); b.textContent = ${JSONObject.quote(nextLabel ?: "")};
              var sp = document.createElement('span'); sp.textContent = ${JSONObject.quote(nextTitle ?: "")};
              a.appendChild(sm); a.appendChild(b); a.appendChild(sp);
              pc.appendChild(a);
            }
          }
          if (window.__gvRelax) window.__gvRelax();
          if (window.__gvApply) window.__gvApply(document.getElementById('gv-reader-css') ?
              document.getElementById('gv-reader-css').textContent : '');
          var t = document.getElementById('page-title');
          var r = document.querySelector('.rate-points .number');
          var tags = [].map.call(document.querySelectorAll('.page-tags a'), function(a){ return a.textContent.trim(); });
          return JSON.stringify({
            title: t ? t.textContent.trim() : document.title,
            rating: r ? r.textContent.trim() : '',
            tags: tags
          });
        })();
    """.trimIndent()

    fun expandAllJs() = """
        document.querySelectorAll('.collapsible-block-folded').forEach(function(f){
          f.style.display='none'; var u=f.parentNode.querySelector('.collapsible-block-unfolded'); if(u) u.style.display='block';
        });
    """.trimIndent()

    fun scrollToFractionJs(f: Float) =
        "window.scrollTo(0, ${f} * (document.documentElement.scrollHeight - window.innerHeight));"

    fun scrollByScreenJs(dir: Int) =
        "window.scrollBy({top: ${dir} * window.innerHeight * 0.88, behavior: 'smooth'});"

    /** Object class from the page tags, with a display colour. */
    fun objectClass(tags: List<String>): Pair<String, Int>? {
        val classes = listOf(
            "safe" to 0xFF6FCF7F, "euclid" to 0xFFE6C84A, "keter" to 0xFFE5534B, "thaumiel" to 0xFFB48EF0,
            "apollyon" to 0xFFFF3B30, "archon" to 0xFF5FB3E6, "neutralized" to 0xFF9E9E9E,
            "explained" to 0xFF9E9E9E, "decommissioned" to 0xFF9E9E9E, "pending" to 0xFFBDBDBD,
            "esoteric-class" to 0xFFE69A5F, "ticonderoga" to 0xFF5FD3C6, "cernunnos" to 0xFF8BC34A,
        )
        for ((name, color) in classes) {
            if (name in tags) return name.removeSuffix("-class").replaceFirstChar { it.uppercase() } to color.toInt()
        }
        return null
    }

    private val AD_HOSTS = listOf(
        "nitropay.com", "nitropay.net", "doubleclick.net", "googlesyndication.com", "googletagservices.com",
        "google-analytics.com", "googletagmanager.com", "adservice.google.com", "amazon-adsystem.com",
        "adnxs.com", "pubmatic.com", "rubiconproject.com", "openx.net", "criteo.com", "criteo.net",
        "casalemedia.com", "quantserve.com", "quantcount.com", "scorecardresearch.com", "taboola.com",
        "outbrain.com", "moatads.com", "3lift.com", "sharethrough.com", "indexww.com", "lijit.com",
        "sovrn.com", "teads.tv", "smartadserver.com", "gumgum.com", "33across.com", "connatix.com",
        "confiant-integrations.net", "ezoic.net", "adsafeprotected.com", "id5-sync.com", "media.net",
        "yieldmo.com", "onetag-sys.com", "rlcdn.com", "bidswitch.net", "2mdn.net", "adform.net",
    )

    fun isAdHost(host: String?): Boolean {
        val h = host?.lowercase() ?: return false
        return AD_HOSTS.any { h == it || h.endsWith(".$it") }
    }
}
