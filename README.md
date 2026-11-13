# Eneo tal-till-text

Klienter för att spela in tal och skicka till **Eneo** för omvandling till text (transkribering/sammanfattning via vald Eneo-app).

iOS och Android delar samma arbetsflöde. Under [`Teams/`](Teams/) finns en personal tab för Microsoft Teams med samma API-flöde.

| | iOS | Android | Teams |
|---|---|---|---|
| Mapp | [`iOS/`](iOS/) | [`Android/`](Android/) | [`Teams/`](Teams/) |
| Teknik | SwiftUI, SwiftData | Kotlin, Jetpack Compose, Room | Vite, React, TeamsJS |
| Bundle / package | `se.iteca.ciklid.Eneo-tal-till-text` | `se.iteca.eneotaltilltext` | Teams personal tab |
| Min OS | iOS 26.4 | API 33 (Android 13) | Teams Desktop (HTTPS-host) |

---

## Vad apparna gör

1. **Spela in** ljud med mikrofon (paus/återuppta, vågform, max ca 2 timmar).
2. **Spara lokalt** — inspelningar lagras på enheten och syns i en lista.
3. **Logga in mot Eneo** och välja vilken Eneo-app som ska omvandla ljudet.
4. **Ladda upp** inspelningen och hämta tillbaka resultatet som markdown-text.
5. **Läsa, kopiera och dela** resultatet (formaterad text vid kopiering, inte rå markdown).

Övrigt som ingår på båda sidor:

- Importera befintliga ljudfiler
- Gallring efter valfritt antal dagar (retention)
- Återställning av inspelningar som avbrutits (segmentfiler sätts ihop vid start)
- Onboarding för serveradress och inloggning

---

## Repostruktur

```
.
├── Android/          # Android-app (Gradle)
├── iOS/              # iOS-app (Xcode)
│   ├── Eneo tal-till-text.xcodeproj
│   ├── Eneo tal-till-text/          # källkod
│   └── Eneo tal-till-textTests/     # unit tests
├── Teams/            # Microsoft Teams personal tab (Vite/React)
└── README.md
```

Hemligheter (keystore, `local.properties`, `.env` m.m.) ingår **inte** i repot — se `.gitignore`.

---

## iOS

### Öppna projektet

```bash
open "iOS/Eneo tal-till-text.xcodeproj"
```

Välj schemat **Eneo tal-till-text** och kör på simulator eller enhet.

### Krav

- Xcode med iOS 26.4 SDK (eller senare som projektet anger)
- Apple Developer-konto för enhets-/App Store-byggen

### Tester

Unit tests ligger i `iOS/Eneo tal-till-textTests/` (t.ex. filnamn, retention, bas-URL). Kör via Product → Test i Xcode, eller:

```bash
cd iOS
xcodebuild test -scheme "Eneo tal-till-text" -destination 'platform=iOS Simulator,name=iPhone 17'
```

### Viktiga mappar i källan

| Mapp | Innehåll |
|------|----------|
| `Models/` | `Recording` m.m. |
| `Services/` | Inspelning, uppladdning, auth, persistence |
| `Views/` | SwiftUI-skärmar och komponenter |
| `Utilities/` | t.ex. markdown-export till urklipp |

---

## Android

### Öppna / bygga

Öppna mappen `Android/` i Android Studio, eller bygg från terminal:

```bash
cd Android
./gradlew :app:assembleDebug
```

Release-AAB till Play Store:

```bash
cd Android
./gradlew :app:bundleRelease
```

AAB hamnar under build-output (projektet speglar build-katalogen till en cache under `~/Library/Caches/...`).

### Krav

- JDK 17
- Android SDK (API 33–37 enligt `app/build.gradle.kts`)

### Signering (release)

1. Skapa eller använd en lokal `.jks` (ligger t.ex. utanför repot under `AndroidKeystore/`).
2. Kopiera `Android/keystore.properties.example` → `Android/keystore.properties`.
3. Fyll i `storeFile`, `storePassword`, `keyAlias`, `keyPassword`.

`keystore.properties` och `*.jks` är gitignorade och ska aldrig checkas in.

### Tester

```bash
cd Android
./gradlew :app:testDebugUnitTest
```

### Viktiga paket i källan

| Paket | Innehåll |
|-------|----------|
| `audio/` | Inspelning, segment, foreground service |
| `data/` | Room, filer, retention |
| `network/` | Eneo API, auth, uppladdning |
| `ui/` | Compose-skärmar |
| `markdown/` | Visning + HTML/plain-export |

---

## Gemensamt beteende att känna till

- **Segmentinspelning** — ljud skrivs i korta delar och slås ihop vid stopp eller vid appstart om processen dog.
- **Bakgrund** — Android har en inspelningsnotis; att svepa bort appen från senaste appar stoppar och sparar inspelningen. iOS har motsvarande livscykelhantering.
- **Resultat** — visas formaterat i appen; **Kopiera** lägger HTML + läsbar text i urklipp så att t.ex. Mail behåller rubriker och listor bättre än rå markdown.

---

## Teams

Personal tab som återanvänder samma Eneo-API (auth med 2FA, files/runs/poll). Standard bas-URL är `https://eneo.kommun.se` som i mobilapparna.

```bash
cd Teams
npm install
npm run dev
```

Se [`Teams/README.md`](Teams/README.md) för HTTPS-host, `redirect_uri` hos Eneo/IdP och sideload av manifest.

---

## Bidra / utveckla

1. Klona repot.
2. Jobba i `iOS/`, `Android/` eller `Teams/` (håll klienterna i synk vid gemensamma beteendeförändringar).
3. Checka inte in hemligheter eller lokala SDK-sökvägar.

Frågor kring Eneo-servern (appar, tokens, ACL) hör till backend/plattformen — den här repot innehåller bara klienterna. Mer om Eneo finns på [eneo.ai](https://eneo.ai).
