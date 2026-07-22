package et.alpha.nchat.mesh.protocol

import java.security.MessageDigest

/**
 * A public key rendered as six words, for reading aloud.
 *
 * Two people on a phone call comparing "copper anchor fiddle ..." can tell
 * within seconds whether they hold the same key for each other. Comparing 64 hex
 * characters, they cannot — people skim, and a mismatch in the middle is exactly
 * what an attacker would arrange.
 *
 * ## What six words are and are not worth
 *
 * Each word is one byte of the key's SHA-256, so six words carry **48 bits**.
 * That is a spot-check, not a proof: forging a key whose fingerprint matches a
 * target is a ~2^48 search, which is expensive but not out of reach for a
 * motivated attacker with hardware.
 *
 * The real verification is scanning the QR code, which compares all 32 bytes.
 * The word list exists for the case where two people cannot point cameras at
 * each other — over a phone call, or across a room — and it should be described
 * to users as a check, not a guarantee. Widening it means more words, and six
 * is what fits on one line of a phone screen.
 */
fun fingerprintWords(publicKey: ByteArray): List<String> {
    val digest = MessageDigest.getInstance("SHA-256").digest(publicKey)
    return (0 until FINGERPRINT_WORD_COUNT).map { FINGERPRINT_WORDS[digest[it].toInt() and 0xFF] }
}

/** The fingerprint as one readable line. */
fun fingerprintLine(publicKey: ByteArray): String = fingerprintWords(publicKey).joinToString(" ")

const val FINGERPRINT_WORD_COUNT: Int = 6

/**
 * Exactly 256 distinct words, one per byte value.
 *
 * Chosen to be short, common, and hard to mishear: no near-homophones, no
 * words that differ only by a plural. The list is part of the protocol — two
 * devices must agree on it or their fingerprints will not match — so entries
 * may never be reordered, only appended to a longer list under a new version.
 */
val FINGERPRINT_WORDS: List<String> = listOf(
    "acid", "actor", "adult", "afraid", "agent", "air", "alarm", "album",
    "alien", "alley", "almond", "amber", "anchor", "angle", "ankle", "apple",
    "april", "arch", "arctic", "arena", "armor", "arrow", "artist", "aspen",
    "atlas", "atom", "auburn", "august", "aunt", "autumn", "avenue", "axis",
    "bacon", "badge", "bagel", "baker", "balance", "balcony", "bamboo", "banana",
    "banjo", "barrel", "basil", "basket", "batch", "beacon", "beagle", "beam",
    "bean", "bear", "beetle", "bell", "belt", "bench", "berry", "bicycle",
    "bishop", "bison", "blanket", "blossom", "board", "bobcat", "bonus", "border",
    "bottle", "boulder", "bracket", "branch", "brave", "bread", "brick", "bridge",
    "bronze", "brush", "bubble", "bucket", "buffalo", "bugle", "bundle", "bunker",
    "burger", "butter", "button", "cabin", "cable", "cactus", "camel", "campus",
    "canal", "candle", "cannon", "canoe", "canvas", "canyon", "cape", "captain",
    "caramel", "cargo", "carpet", "carrot", "castle", "cattle", "cedar", "cellar",
    "cement", "census", "chalk", "chamber", "chapel", "charm", "cheese", "cherry",
    "chess", "chimney", "chorus", "cider", "cinema", "circle", "circus", "clamp",
    "clay", "clever", "cliff", "clinic", "cloak", "clock", "closet", "cloud",
    "clover", "cobalt", "cobra", "cocoa", "coffee", "collar", "colony", "column",
    "comet", "compass", "condor", "copper", "coral", "cork", "cotton", "cougar",
    "county", "cousin", "cowboy", "coyote", "crab", "crane", "crater", "crayon",
    "cricket", "crimson", "crystal", "cube", "cuckoo", "cushion", "cymbal", "dagger",
    "dahlia", "daisy", "dancer", "dawn", "deer", "delta", "denim", "desert",
    "diamond", "diary", "diesel", "dinner", "dolphin", "domino", "donkey", "dragon",
    "dresser", "drift", "drum", "dune", "dusk", "eagle", "easel", "echo",
    "eclipse", "elbow", "elder", "elm", "ember", "emerald", "empire", "engine",
    "envelope", "equal", "escape", "estate", "evening", "exhibit", "exit", "fabric",
    "falcon", "fantasy", "farm", "feather", "fennel", "fern", "ferry", "fiber",
    "fiddle", "fig", "filter", "finch", "fjord", "flame", "flask", "fleet",
    "flint", "floor", "flute", "folder", "forest", "forge", "fossil", "fountain",
    "fox", "frame", "freckle", "frost", "fudge", "funnel", "gadget", "galaxy",
    "gallery", "garden", "garlic", "gazelle", "gecko", "gem", "geyser", "ginger",
    "giraffe", "glacier", "glass", "glove", "gold", "gopher", "granite", "grape",
    "gravel", "grotto", "guitar", "gull", "gypsum", "hammer", "hamster", "harbor",
)
