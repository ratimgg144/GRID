# Run only on your trusted Windows computer with JDK installed.
# DO NOT upload or commit grid-release.jks. Store two secure backups.
keytool -genkeypair -v -storetype PKCS12 -keystore grid-release.jks -alias grid -keyalg RSA -keysize 3072 -validity 10000
if ($LASTEXITCODE -ne 0) { throw "Key creation failed" }
Write-Host 'Created grid-release.jks. Back up the file plus the passwords before publishing any update.'
Write-Host 'The Base64 keystore secret is copied to your clipboard.'
[Convert]::ToBase64String([IO.File]::ReadAllBytes((Resolve-Path 'grid-release.jks'))) | Set-Clipboard
