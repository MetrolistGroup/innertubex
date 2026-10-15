package com.metrolist.innertubex.cipher

internal object EjsPlayerFixture {
    const val LARGE_PLAYER_JS_LENGTH = 2 * 1024 * 1024
    const val PLAYER_URL = "https://www.youtube.com/s/player/12345678/player.js"
    const val OTHER_PLAYER_URL = "https://www.youtube.com/s/player/87654321/player.js"
    val PLAYER_CODE =
        """
        (function() {
          function PlayerUrl(url, key, signature) {
            this.values = {s: signature || null, n: null};
          }
          PlayerUrl.prototype.set = function(key, value) { this.values[key] = value; };
          PlayerUrl.prototype.get = function(key) { return this.values[key]; };
          PlayerUrl.prototype.clone = function() { return this; };
          PlayerUrl.prototype.apply = function() {
            if (this.values.s) this.values.s = this.values.s.split("").reverse().join("");
            if (this.values.n) this.values.n += "_done";
          };
          function transform(url, key, signature) {
            var value = new PlayerUrl(url, key, signature);
            value.set("alr", "yes");
            return value;
          }
        }).call(this);
        """.trimIndent()
}
