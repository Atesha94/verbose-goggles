Pod::Spec.new do |s|
  s.name           = 'TempoHealth'
  s.version        = '1.0.0'
  s.summary        = 'HealthKit + WatchConnectivity bridge for Tempo'
  s.author         = 'Tempo'
  s.homepage       = 'https://example.invalid/tempo'
  s.license        = 'MIT'
  s.platforms      = { :ios => '17.0' }
  s.source         = { git: '' }
  s.static_framework = true
  s.dependency 'ExpoModulesCore'
  s.frameworks     = 'HealthKit', 'WatchConnectivity'
  s.source_files   = '**/*.swift'
end
