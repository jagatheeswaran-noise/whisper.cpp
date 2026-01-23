import Foundation
import os.log

/// Result of contact matching with confidence score
struct ContactMatchResult {
    let matchedName: String?
    let confidence: Float
    let matchType: MatchType
}

enum MatchType {
    case exact
    case fuzzy
    case noMatch
}

/// Pre-normalized contact data for fast matching
private struct NormalizedContact {
    let originalName: String
    let parts: [String]  // ["Ayush", "Kumar", "Singh"]
    let phoneticParts: [String]  // Phonetically normalized parts
    let phoneticFullName: String  // Phonetically normalized full name without spaces
    let exactCombinations: [String: String]  // All sub-name combinations: "first", "last", "first_last", etc.
    let phoneticCombinations: [String: String]  // Phonetically normalized combinations
}

/// ContactMatcher handles fuzzy matching for contact names
/// Uses pre-computed phonetic normalization and Levenshtein distance
class ContactMatcher {
    private static let logger = Logger(subsystem: "com.whispercpp.demo", category: "ContactMatcher")
    
    private var contacts: [String] = []
    private var normalizedContacts: [NormalizedContact] = []
    private let confidenceThreshold: Float = 80.0
    
    init() {
        loadContacts()
    }
    
    /// Load contacts from contacts.json and pre-compute phonetic normalizations
    func loadContacts() {
        do {
            guard let url = Bundle.main.url(forResource: "contacts", withExtension: "json") else {
                Self.logger.error("Could not find contacts.json")
                return
            }
            
            let data = try Data(contentsOf: url)
            let json = try JSONSerialization.jsonObject(with: data) as? [String: Any]
            
            if let contactsArray = json?["contacts"] as? [String] {
                contacts = contactsArray
                
                // Pre-compute phonetic normalizations for all contacts
                normalizedContacts = contacts.map { normalizeContact($0) }
                
                Self.logger.info("Loaded \(self.contacts.count) contacts with pre-computed phonetic normalizations")
            }
        } catch {
            Self.logger.error("Error loading contacts: \(error.localizedDescription)")
            contacts = []
            normalizedContacts = []
        }
    }
    
    /// Pre-compute all name combinations and phonetic normalizations for a contact
    private func normalizeContact(_ name: String) -> NormalizedContact {
        let parts = name.trimmingCharacters(in: .whitespaces).components(separatedBy: " ").filter { !$0.isEmpty }
        let lowerParts = parts.map { $0.lowercased() }
        let phoneticParts = lowerParts.map { phoneticNormalize($0) }
        let phoneticFullName = phoneticNormalize(lowerParts.joined())
        
        var exactCombinations: [String: String] = [:]
        var phoneticCombinations: [String: String] = [:]
        
        // 1. Generate all contiguous subsequences
        for start in 0..<parts.count {
            for end in start..<parts.count {
                let subParts = Array(lowerParts[start...end])
                let subPartsPhonetic = Array(phoneticParts[start...end])
                
                // Create key based on position
                let key: String
                if start == 0 && end == parts.count - 1 {
                    key = "full"
                } else if start == 0 && end == 0 {
                    key = "first"
                } else if start == parts.count - 1 && end == parts.count - 1 {
                    key = "last"
                } else if start == 0 {
                    key = "first_\(end + 1)"  // first 2, first 3, etc.
                } else if end == parts.count - 1 {
                    key = "last_\(parts.count - start)"  // last 2, last 3, etc.
                } else {
                    key = "mid_\(start)_\(end)"
                }
                
                exactCombinations[key] = subParts.joined(separator: " ")
                phoneticCombinations[key] = subPartsPhonetic.joined()
            }
        }
        
        // 2. Generate non-consecutive combinations (first+last, first+second_last, etc.)
        if parts.count >= 3 {
            // First + Last (e.g., "ayush singh" for "Ayush Kumar Singh")
            let firstLast = [lowerParts[0], lowerParts[parts.count - 1]]
            let firstLastPhonetic = [phoneticParts[0], phoneticParts[parts.count - 1]]
            exactCombinations["first_last"] = firstLast.joined(separator: " ")
            phoneticCombinations["first_last"] = firstLastPhonetic.joined()
            
            // For names with 4+ parts, also add first + second_last
            if parts.count >= 4 {
                let firstSecondLast = [lowerParts[0], lowerParts[parts.count - 2]]
                let firstSecondLastPhonetic = [phoneticParts[0], phoneticParts[parts.count - 2]]
                exactCombinations["first_second_last"] = firstSecondLast.joined(separator: " ")
                phoneticCombinations["first_second_last"] = firstSecondLastPhonetic.joined()
            }
        }
        
        return NormalizedContact(
            originalName: name,
            parts: lowerParts,
            phoneticParts: phoneticParts,
            phoneticFullName: phoneticFullName,
            exactCombinations: exactCombinations,
            phoneticCombinations: phoneticCombinations
        )
    }
    
    /**
     * Match a name against the contact list using optimized priority order:
     * PHASE 1: All exact matches (full name, then all sub-name combinations)
     * PHASE 2: Fuzzy matches with pre-computed phonetic normalizations (full name, then all sub-name combinations)
     */
    func matchContact(_ inputName: String) -> ContactMatchResult {
        Self.logger.info("\n\(String(repeating: "=", count: 50))")
        Self.logger.info("CONTACT MATCHING STARTED")
        Self.logger.info("Input name: '\(inputName)'")
        Self.logger.info("Confidence threshold: \(self.confidenceThreshold)%")
        
        if normalizedContacts.isEmpty {
            loadContacts()
        }
        
        if normalizedContacts.isEmpty {
            Self.logger.error("ERROR: No contacts loaded!")
            return ContactMatchResult(matchedName: nil, confidence: 0, matchType: .noMatch)
        }
        
        let normalizedInput = inputName.trimmingCharacters(in: .whitespaces).lowercased()
        let inputParts = normalizedInput.components(separatedBy: " ").filter { !$0.isEmpty }
        
        Self.logger.info("Input parts: \(inputParts.joined(separator: ", "))")
        
        // ===== PHASE 1: EXACT MATCHES =====
        Self.logger.info("\n--- PHASE 1: EXACT MATCHES ---")
        
        // Try exact full name match
        if let exactFullMatch = exactMatchFullName(normalizedInput) {
            return exactFullMatch
        }
        
        // Try all exact sub-name combinations
        if let exactSubMatch = exactMatchSubNames(inputParts) {
            return exactSubMatch
        }
        
        // ===== PHASE 2: FUZZY MATCHES WITH PHONETIC NORMALIZATION =====
        Self.logger.info("\n--- PHASE 2: FUZZY MATCHES (PHONETICALLY NORMALIZED) ---")
        
        // Phonetically normalize input (only once!)
        let phoneticInput = phoneticNormalize(normalizedInput.replacingOccurrences(of: " ", with: ""))
        Self.logger.info("Phonetically normalized input: '\(phoneticInput)'")
        
        // Try fuzzy full name match
        if let fuzzyFullMatch = fuzzyMatchFullName(phoneticInput) {
            return fuzzyFullMatch
        }
        
        // Try all fuzzy sub-name combinations
        if let fuzzySubMatch = fuzzyMatchSubNames(inputParts) {
            return fuzzySubMatch
        }
        
        Self.logger.info("\n✗ NO MATCH FOUND for: '\(inputName)'")
        Self.logger.info("All matches were below \(confidenceThreshold)% confidence threshold")
        Self.logger.info(String(repeating: "=", count: 50))
        return ContactMatchResult(matchedName: nil, confidence: 0, matchType: .noMatch)
    }
    
    /// EXACT: Try matching full name exactly
    private func exactMatchFullName(_ normalizedInput: String) -> ContactMatchResult? {
        Self.logger.info("Checking exact full name match...")
        
        for contact in normalizedContacts {
            if let contactFullName = contact.exactCombinations["full"], contactFullName == normalizedInput {
                Self.logger.info("✓ EXACT FULL NAME MATCH: \(contact.originalName)")
                Self.logger.info(String(repeating: "=", count: 50))
                return ContactMatchResult(matchedName: contact.originalName, confidence: 100, matchType: .exact)
            }
        }
        
        Self.logger.info("✗ No exact full name match")
        return nil
    }
    
    /// EXACT: Try matching full input against all contact sub-name combinations
    private func exactMatchSubNames(_ inputParts: [String]) -> ContactMatchResult? {
        Self.logger.info("Checking exact sub-name matches...")
        
        // Take the FULL input as a single string (don't split it)
        let fullInput = inputParts.joined(separator: " ")
        Self.logger.info("  Full input: '\(fullInput)'")
        
        for contact in normalizedContacts {
            // Check full input against all contact sub-name combinations
            for (key, exactValue) in contact.exactCombinations {
                if exactValue == fullInput {
                    Self.logger.info("✓ EXACT SUB-NAME MATCH: \(contact.originalName) (matched '\(fullInput)' with \(key))")
                    Self.logger.info(String(repeating: "=", count: 50))
                    return ContactMatchResult(matchedName: contact.originalName, confidence: 95, matchType: .exact)
                }
            }
        }
        
        Self.logger.info("✗ No exact sub-name match")
        return nil
    }
    
    /// FUZZY: Try fuzzy matching full name (using pre-computed phonetic normalization)
    private func fuzzyMatchFullName(_ phoneticInput: String) -> ContactMatchResult? {
        Self.logger.info("Checking fuzzy full name match...")
        
        var bestMatch: ContactMatchResult? = nil
        var bestScore: Float = 0
        
        for contact in normalizedContacts {
            let similarity = levenshteinSimilarity(phoneticInput, contact.phoneticFullName) * 100
            
            if similarity >= 50 {
                Self.logger.info("  \(contact.originalName): \(String(format: "%.1f", similarity))% similarity")
            }
            
            if similarity > bestScore {
                bestScore = similarity
                if similarity >= confidenceThreshold {
                    bestMatch = ContactMatchResult(matchedName: contact.originalName, confidence: similarity, matchType: .fuzzy)
                }
            }
        }
        
        if let match = bestMatch {
            Self.logger.info("✓ FUZZY FULL NAME MATCH: \(match.matchedName!) (\(String(format: "%.1f", match.confidence))%)")
            Self.logger.info(String(repeating: "=", count: 50))
            return match
        }
        
        Self.logger.info("✗ No fuzzy full name match above threshold (best: \(String(format: "%.1f", bestScore))%)")
        return nil
    }
    
    /// FUZZY: Try fuzzy matching full input against all contact sub-name combinations (using pre-computed phonetic normalizations)
    private func fuzzyMatchSubNames(_ inputParts: [String]) -> ContactMatchResult? {
        Self.logger.info("Checking fuzzy sub-name matches...")
        
        // Take the FULL input as a single string (don't split it)
        let fullInput = inputParts.joined()
        let phoneticFullInput = phoneticNormalize(fullInput)
        Self.logger.info("  Full input: '\(inputParts.joined(separator: " "))' → phonetic: '\(phoneticFullInput)'")
        
        var bestMatch: ContactMatchResult? = nil
        var bestScore: Float = 0
        
        for contact in normalizedContacts {
            // Check full input against all phonetic contact sub-name combinations
            for (_, phoneticValue) in contact.phoneticCombinations {
                let similarity = levenshteinSimilarity(phoneticFullInput, phoneticValue) * 100
                
                if similarity > bestScore {
                    bestScore = similarity
                    if similarity >= confidenceThreshold {
                        bestMatch = ContactMatchResult(matchedName: contact.originalName, confidence: similarity, matchType: .fuzzy)
                    }
                }
            }
        }
        
        if let match = bestMatch {
            Self.logger.info("✓ FUZZY SUB-NAME MATCH: \(match.matchedName!) (\(String(format: "%.1f", match.confidence))%)")
            Self.logger.info(String(repeating: "=", count: 50))
            return match
        }
        
        Self.logger.info("✗ No fuzzy sub-name match above threshold (best: \(String(format: "%.1f", bestScore))%)")
        return nil
    }
    
    /// Calculate similarity percentage using Levenshtein distance
    private func levenshteinSimilarity(_ s1: String, _ s2: String) -> Float {
        let distance = levenshteinDistance(s1, s2)
        let maxLength = max(s1.count, s2.count)
        return maxLength == 0 ? 1.0 : (1.0 - Float(distance) / Float(maxLength))
    }
    
    /// Calculate Levenshtein distance between two strings
    private func levenshteinDistance(_ s1: String, _ s2: String) -> Int {
        let len1 = s1.count
        let len2 = s2.count
        
        var dp = Array(repeating: Array(repeating: 0, count: len2 + 1), count: len1 + 1)
        
        for i in 0...len1 {
            dp[i][0] = i
        }
        
        for j in 0...len2 {
            dp[0][j] = j
        }
        
        let s1Array = Array(s1)
        let s2Array = Array(s2)
        
        for i in 1...len1 {
            for j in 1...len2 {
                let cost = s1Array[i - 1] == s2Array[j - 1] ? 0 : 1
                dp[i][j] = min(
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
     */
    private func phoneticNormalize(_ name: String) -> String {
        var result = name.lowercased()
        
        // Rule 1: Aspirated consonants → unaspirated
        // Process in order from longest to shortest to avoid partial replacements
        result = result.replacingOccurrences(of: "th", with: "t")
        result = result.replacingOccurrences(of: "dh", with: "d")
        result = result.replacingOccurrences(of: "kh", with: "k")
        result = result.replacingOccurrences(of: "gh", with: "g")
        result = result.replacingOccurrences(of: "ph", with: "f")
        result = result.replacingOccurrences(of: "bh", with: "b")
        result = result.replacingOccurrences(of: "ch", with: "c")
        result = result.replacingOccurrences(of: "jh", with: "j")
        result = result.replacingOccurrences(of: "sh", with: "s")
        
        // Rule 2: Long vowels → short vowels
        result = result.replacingOccurrences(of: "aa", with: "a")
        result = result.replacingOccurrences(of: "ee", with: "i")
        result = result.replacingOccurrences(of: "ii", with: "i")
        result = result.replacingOccurrences(of: "oo", with: "u")
        result = result.replacingOccurrences(of: "uu", with: "u")
        
        // Rule 3: Diphthongs → simple vowels
        result = result.replacingOccurrences(of: "ai", with: "e")
        result = result.replacingOccurrences(of: "ay", with: "e")
        result = result.replacingOccurrences(of: "au", with: "o")
        
        // Rule 4: V/W normalization (both to 'w')
        result = result.replacingOccurrences(of: "v", with: "w")
        
        // Rule 5: K/C normalization (both to 'k') - handles "kaul" vs "call"
        result = result.replacingOccurrences(of: "c", with: "k")
        
        // Rule 6: Double consonants → single
        if let regex = try? NSRegularExpression(pattern: "(.)\\1+", options: []) {
            let range = NSRange(result.startIndex..., in: result)
            result = regex.stringByReplacingMatches(in: result, options: [], range: range, withTemplate: "$1")
        }
        
        // Note: Ending vowels are already preserved (not modified by these rules)
        
        return result
    }
}
