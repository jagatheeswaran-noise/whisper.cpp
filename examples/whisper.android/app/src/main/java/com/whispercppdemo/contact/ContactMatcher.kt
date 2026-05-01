package com.whispercppdemo.contact

import android.content.Context
import android.util.Log
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

private const val LOG_TAG = "ContactMatcher"

/**
 * Result of contact matching with confidence score
 */
data class ContactMatchResult(
    val matchedName: String?,
    val confidence: Float,
    val matchType: MatchType
)

enum class MatchType {
    EXACT,
    FUZZY,
    NO_MATCH
}

/**
 * Pre-normalized contact data for fast matching
 */
private data class NormalizedContact(
    val originalName: String,
    val parts: List<String>,  // ["Ayush", "Kumar", "Singh"]
    val phoneticParts: List<String>,  // Phonetically normalized parts
    val phoneticFullName: String,  // Phonetically normalized full name without spaces
    val exactCombinations: Map<String, String>,  // All sub-name combinations: "first", "last", "firstmid", etc.
    val phoneticCombinations: Map<String, String>  // Phonetically normalized combinations
)

/**
 * ContactMatcher handles fuzzy matching for contact names
 * Uses pre-computed phonetic normalization and Levenshtein distance
 */
class ContactMatcher(private val context: Context) {
    private var contacts: List<String> = emptyList()
    private var normalizedContacts: List<NormalizedContact> = emptyList()
    private val confidenceThreshold = 80.0f

    
    /**
     * Load contacts from contacts.json and pre-compute phonetic normalizations
     */
    fun loadContacts() {
        try {
            val contactsJson = context.assets.open("contacts.json").bufferedReader().use { it.readText() }
            val jsonObject = JSONObject(contactsJson)
            val contactsArray = jsonObject.getJSONArray("contacts")
            
            contacts = List(contactsArray.length()) { i ->
                contactsArray.getString(i)
            }
            
            // Pre-compute phonetic normalizations for all contacts
            normalizedContacts = contacts.map { contact ->
                normalizeContact(contact)
            }
            
            Log.d(LOG_TAG, "Loaded ${contacts.size} contacts with pre-computed phonetic normalizations")
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Error loading contacts", e)
            contacts = emptyList()
            normalizedContacts = emptyList()
        }
    }
    
    /**
     * Pre-compute all name combinations and phonetic normalizations for a contact
     */
    private fun normalizeContact(name: String): NormalizedContact {
        val parts = name.trim().split(" ").filter { it.isNotBlank() }
        val lowerParts = parts.map { it.lowercase() }
        val phoneticParts = lowerParts.map { phoneticNormalize(it) }
        val phoneticFullName = phoneticNormalize(lowerParts.joinToString(""))
        
        val exactCombinations = mutableMapOf<String, String>()
        val phoneticCombinations = mutableMapOf<String, String>()
        
        // 1. Generate all contiguous subsequences
        for (start in parts.indices) {
            for (end in start until parts.size) {
                val subParts = lowerParts.subList(start, end + 1)
                val subPartsPhonetic = phoneticParts.subList(start, end + 1)
                
                // Create key based on position
                val key = when {
                    start == 0 && end == parts.size - 1 -> "full"
                    start == 0 && end == 0 -> "first"
                    start == parts.size - 1 && end == parts.size - 1 -> "last"
                    start == 0 -> "first_${end + 1}"  // first 2, first 3, etc.
                    end == parts.size - 1 -> "last_${parts.size - start}"  // last 2, last 3, etc.
                    else -> "mid_${start}_${end}"
                }
                
                exactCombinations[key] = subParts.joinToString(" ")
                phoneticCombinations[key] = subPartsPhonetic.joinToString("")
            }
        }
        
        // 2. Generate non-consecutive combinations (first+last, first+second_last, etc.)
        if (parts.size >= 3) {
            // First + Last (e.g., "ayush singh" for "Ayush Kumar Singh")
            val firstLast = listOf(lowerParts[0], lowerParts[parts.size - 1])
            val firstLastPhonetic = listOf(phoneticParts[0], phoneticParts[parts.size - 1])
            exactCombinations["first_last"] = firstLast.joinToString(" ")
            phoneticCombinations["first_last"] = firstLastPhonetic.joinToString("")
            
            // For names with 4+ parts, also add first + second_last
            if (parts.size >= 4) {
                val firstSecondLast = listOf(lowerParts[0], lowerParts[parts.size - 2])
                val firstSecondLastPhonetic = listOf(phoneticParts[0], phoneticParts[parts.size - 2])
                exactCombinations["first_second_last"] = firstSecondLast.joinToString(" ")
                phoneticCombinations["first_second_last"] = firstSecondLastPhonetic.joinToString("")
            }
        }
        
        return NormalizedContact(
            originalName = name,
            parts = lowerParts,
            phoneticParts = phoneticParts,
            phoneticFullName = phoneticFullName,
            exactCombinations = exactCombinations,
            phoneticCombinations = phoneticCombinations
        )
    }
    
    /**
     * Clean punctuation from input string
     */
    private fun cleanPunctuation(input: String): String {
        // Remove common punctuation marks that might appear in transcriptions
        return input.replace(Regex("[,;.!?:\"'`]"), "")
            .replace(Regex("\\s+"), " ")  // Normalize multiple spaces to single space
            .trim()
    }
    
    /**
     * Match a name against the contact list using optimized priority order:
     * PHASE 1: All exact matches (full name, then all sub-name combinations)
     * PHASE 2: Fuzzy matches with pre-computed phonetic normalizations (full name, then all sub-name combinations)
     */
    fun matchContact(inputName: String): ContactMatchResult {
        Log.d(LOG_TAG, "\n" + "=".repeat(50))
        Log.d(LOG_TAG, "CONTACT MATCHING STARTED")
        Log.d(LOG_TAG, "Input name: '$inputName'")
        
        // Clean punctuation from input first
        val cleanedInput = cleanPunctuation(inputName)
        if (cleanedInput != inputName) {
            Log.d(LOG_TAG, "Cleaned input: '$cleanedInput' (removed punctuation)")
        }
        Log.d(LOG_TAG, "Confidence threshold: ${confidenceThreshold}%")
        
        if (normalizedContacts.isEmpty()) {
            loadContacts()
        }
        
        if (normalizedContacts.isEmpty()) {
            Log.d(LOG_TAG, "ERROR: No contacts loaded!")
            return ContactMatchResult(null, 0f, MatchType.NO_MATCH)
        }
        
        val normalizedInput = cleanedInput.trim().lowercase()
        val inputParts = normalizedInput.split(" ").filter { it.isNotBlank() }
        
        Log.d(LOG_TAG, "Input parts: ${inputParts.joinToString(", ")}")
        
        // ===== PHASE 1: EXACT MATCHES =====
        Log.d(LOG_TAG, "\n--- PHASE 1: EXACT MATCHES ---")
        
        // Try exact full name match
        val exactFullMatch = exactMatchFullName(normalizedInput)
        if (exactFullMatch != null) return exactFullMatch
        
        // Try all exact sub-name combinations
        val exactSubMatch = exactMatchSubNames(inputParts)
        if (exactSubMatch != null) return exactSubMatch
        
        // ===== PHASE 2: FUZZY MATCHES WITH PHONETIC NORMALIZATION =====
        Log.d(LOG_TAG, "\n--- PHASE 2: FUZZY MATCHES (PHONETICALLY NORMALIZED) ---")
        
        // Phonetically normalize input (only once!)
        val phoneticInput = phoneticNormalize(normalizedInput.replace(" ", ""))
        Log.d(LOG_TAG, "Phonetically normalized input: '$phoneticInput'")
        
        // Try fuzzy full name match
        val fuzzyFullMatch = fuzzyMatchFullName(phoneticInput)
        if (fuzzyFullMatch != null) return fuzzyFullMatch
        
        // Try all fuzzy sub-name combinations
        val fuzzySubMatch = fuzzyMatchSubNames(inputParts)
        if (fuzzySubMatch != null) return fuzzySubMatch
        
        Log.d(LOG_TAG, "\n✗ NO MATCH FOUND for: '$inputName'")
        Log.d(LOG_TAG, "All matches were below ${confidenceThreshold}% confidence threshold")
        Log.d(LOG_TAG, "=".repeat(50) + "\n")
        return ContactMatchResult(null, 0f, MatchType.NO_MATCH)
    }
    
    /**
     * EXACT: Try matching full name exactly
     */
    private fun exactMatchFullName(normalizedInput: String): ContactMatchResult? {
        Log.d(LOG_TAG, "Checking exact full name match...")
        
        for (contact in normalizedContacts) {
            val contactFullName = contact.exactCombinations["full"] ?: continue
            if (contactFullName == normalizedInput) {
                Log.d(LOG_TAG, "✓ EXACT FULL NAME MATCH: ${contact.originalName}")
                Log.d(LOG_TAG, "=".repeat(50) + "\n")
                return ContactMatchResult(contact.originalName, 100f, MatchType.EXACT)
            }
        }
        
        Log.d(LOG_TAG, "✗ No exact full name match")
        return null
    }
    
    /**
     * EXACT: Try matching full input against all contact sub-name combinations
     */
    private fun exactMatchSubNames(inputParts: List<String>): ContactMatchResult? {
        Log.d(LOG_TAG, "Checking exact sub-name matches...")
        
        // Take the FULL input as a single string (don't split it)
        val fullInput = inputParts.joinToString(" ")
        Log.d(LOG_TAG, "  Full input: '$fullInput'")
        
        for (contact in normalizedContacts) {
            // Check full input against all contact sub-name combinations
            for ((key, exactValue) in contact.exactCombinations) {
                if (exactValue == fullInput) {
                    Log.d(LOG_TAG, "✓ EXACT SUB-NAME MATCH: ${contact.originalName} (matched '$fullInput' with $key)")
                    Log.d(LOG_TAG, "=".repeat(50) + "\n")
                    return ContactMatchResult(contact.originalName, 95f, MatchType.EXACT)
                }
            }
        }
        
        Log.d(LOG_TAG, "✗ No exact sub-name match")
        return null
    }
    
    /**
     * FUZZY: Try fuzzy matching full name (using pre-computed phonetic normalization)
     */
    private fun fuzzyMatchFullName(phoneticInput: String): ContactMatchResult? {
        Log.d(LOG_TAG, "Checking fuzzy full name match...")
        
        var bestMatch: ContactMatchResult? = null
        var bestScore = 0f
        
        for (contact in normalizedContacts) {
            val similarity = levenshteinSimilarity(phoneticInput, contact.phoneticFullName) * 100f
            
            if (similarity >= 50f) {
                Log.d(LOG_TAG, "  ${contact.originalName}: ${String.format("%.1f", similarity)}% similarity")
            }
            
            if (similarity > bestScore) {
                bestScore = similarity
                if (similarity >= confidenceThreshold) {
                    bestMatch = ContactMatchResult(contact.originalName, similarity, MatchType.FUZZY)
                }
            }
        }
        
        if (bestMatch != null) {
            Log.d(LOG_TAG, "✓ FUZZY FULL NAME MATCH: ${bestMatch.matchedName} (${String.format("%.1f", bestMatch.confidence)}%)")
            Log.d(LOG_TAG, "=".repeat(50) + "\n")
            return bestMatch
        }
        
        Log.d(LOG_TAG, "✗ No fuzzy full name match above threshold (best: ${String.format("%.1f", bestScore)}%)")
        return null
    }
    
    /**
     * FUZZY: Try fuzzy matching full input against all contact sub-name combinations (using pre-computed phonetic normalizations)
     */
    private fun fuzzyMatchSubNames(inputParts: List<String>): ContactMatchResult? {
        Log.d(LOG_TAG, "Checking fuzzy sub-name matches...")
        
        // Take the FULL input as a single string (don't split it)
        val fullInput = inputParts.joinToString("")
        val phoneticFullInput = phoneticNormalize(fullInput)
        Log.d(LOG_TAG, "  Full input: '${inputParts.joinToString(" ")}' → phonetic: '$phoneticFullInput'")
        
        var bestMatch: ContactMatchResult? = null
        var bestScore = 0f
        
        for (contact in normalizedContacts) {
            // Check full input against all phonetic contact sub-name combinations
            for ((key, phoneticValue) in contact.phoneticCombinations) {
                val similarity = levenshteinSimilarity(phoneticFullInput, phoneticValue) * 100f
                
                if (similarity > bestScore) {
                    bestScore = similarity
                    if (similarity >= confidenceThreshold) {
                        bestMatch = ContactMatchResult(contact.originalName, similarity, MatchType.FUZZY)
                    }
                }
            }
        }
        
        if (bestMatch != null) {
            Log.d(LOG_TAG, "✓ FUZZY SUB-NAME MATCH: ${bestMatch.matchedName} (${String.format("%.1f", bestMatch.confidence)}%)")
            Log.d(LOG_TAG, "=".repeat(50) + "\n")
            return bestMatch
        }
        
        Log.d(LOG_TAG, "✗ No fuzzy sub-name match above threshold (best: ${String.format("%.1f", bestScore)}%)")
        return null
    }
    
    /**
     * Calculate similarity percentage using Levenshtein distance
     */
    private fun levenshteinSimilarity(s1: String, s2: String): Float {
        val distance = levenshteinDistance(s1, s2)
        val maxLength = max(s1.length, s2.length)
        return if (maxLength == 0) 1.0f else (1.0f - distance.toFloat() / maxLength)
    }
    
    /**
     * Calculate Levenshtein distance between two strings
     */
    private fun levenshteinDistance(s1: String, s2: String): Int {
        val len1 = s1.length
        val len2 = s2.length
        
        val dp = Array(len1 + 1) { IntArray(len2 + 1) }
        
        for (i in 0..len1) {
            dp[i][0] = i
        }
        
        for (j in 0..len2) {
            dp[0][j] = j
        }
        
        for (i in 1..len1) {
            for (j in 1..len2) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,      // deletion
                    dp[i][j - 1] + 1,      // insertion
                    dp[i - 1][j - 1] + cost // substitution
                )
            }
        }
        
        return dp[len1][len2]
    }
    
    /**
     * Phonetic normalization for Indian names
     * 
     * Applies rule-based transformations to normalize phonetic variations common in
     * Indian names. The output is a plain string (not syllables with hyphens) that
     * can be used with exact matching or fuzzy matching algorithms.
     * 
     * Normalization rules:
     * 1. Aspirated consonants → unaspirated: th→t, dh→d, kh→k, gh→g, ph→f, bh→b, ch→c, jh→j, sh→s
     * 2. Long vowels → short vowels: aa→a, ee→i, ii→i, oo→u, uu→u
     * 3. Diphthongs → simple vowels: ai→e, ay→e, au→o
     * 4. V/W confusion: both normalized to 'w'
     * 5. K/C confusion: both normalized to 'k'
     * 6. Double consonants → single: ll→l, nn→n, mm→m, etc.
     * 7. Ending vowels are preserved (they distinguish different names)
     * 
     * Examples:
     * - "Thivari" → "tivari"
     * - "Tiwari" → "tiwari" 
     * - "Sharma" → "sarma"
     * - "Preeya" → "priya"
     * - "Kaul" → "kol" (matches "Call" → "kol")
     * 
     * @param name Input name string (assumed to be already lowercase)
     * @return Phonetically normalized string
     */
    private fun phoneticNormalize(name: String): String {
        var result = name.lowercase()
        
        // Rule 1: Aspirated consonants → unaspirated
        // Process in order from longest to shortest to avoid partial replacements
        result = result.replace("th", "t")
        result = result.replace("dh", "d")
        result = result.replace("kh", "k")
        result = result.replace("gh", "g")
        result = result.replace("ph", "f")
        result = result.replace("bh", "b")
        result = result.replace("ch", "c")
        result = result.replace("jh", "j")
        result = result.replace("sh", "s")
        
        // Rule 2: Long vowels → short vowels
        result = result.replace("aa", "a")
        result = result.replace("ee", "i")
        result = result.replace("ii", "i")
        result = result.replace("oo", "u")
        result = result.replace("uu", "u")
        
        // Rule 3: Diphthongs → simple vowels
        result = result.replace("ai", "e")
        result = result.replace("ay", "e")
        result = result.replace("au", "o")
        
        // Rule 4: V/W normalization (both to 'w')
        result = result.replace("v", "w")
        
        // Rule 5: K/C normalization (both to 'k') - handles "kaul" vs "call"
        result = result.replace("c", "k")
        
        // Rule 6: Double consonants → single
        result = result.replace(Regex("(.)\\1+")) { matchResult ->
            matchResult.value[0].toString()
        }
        
        // Note: Ending vowels are already preserved (not modified by these rules)
        
        return result
    }
}
