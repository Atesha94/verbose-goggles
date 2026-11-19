import Foundation

public enum HeartRate {
    public static let validRange = 25...250

    public static func zone(bpm: Int, hrMax: Int) -> Int {
        guard hrMax > 0 else { return 0 }
        let pct = Double(bpm) / Double(hrMax)
        switch pct {
        case 0.9...: return 5
        case 0.8..<0.9: return 4
        case 0.7..<0.8: return 3
        case 0.6..<0.7: return 2
        case 0.5..<0.6: return 1
        default: return 0
        }
    }

    public enum Sex: String, Codable, Sendable { case female, male, unspecified }

    /// Keytel et al. (2005). Mean of both equations when sex is unspecified.
    public static func keytelCalories(avgHr: Double, minutes: Double, weightKg: Double, age: Int, sex: Sex) -> Double {
        let male = (-55.0969 + 0.6309 * avgHr + 0.1988 * weightKg + 0.2017 * Double(age)) / 4.184
        let female = (-20.4022 + 0.4472 * avgHr - 0.1263 * weightKg + 0.074 * Double(age)) / 4.184
        let perMin: Double = switch sex { case .male: male; case .female: female; case .unspecified: (male + female) / 2 }
        return max(0, perMin * minutes)
    }

    public static func metCalories(met: Double, weightKg: Double, minutes: Double) -> Double {
        max(0, met * weightKg * minutes / 60)
    }

    /// Parses Heart Rate Measurement (0x2A37). Returns nil for malformed or implausible packets.
    public static func parseBleMeasurement(_ data: Data) -> Int? {
        let b = [UInt8](data)
        guard let flags = b.first else { return nil }
        let bpm: Int
        if flags & 0x01 == 0 {
            guard b.count >= 2 else { return nil }
            bpm = Int(b[1])
        } else {
            guard b.count >= 3 else { return nil }
            bpm = Int(b[1]) | (Int(b[2]) << 8)
        }
        return validRange.contains(bpm) ? bpm : nil
    }
}
