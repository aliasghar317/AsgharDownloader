# Movies section

The Movies section is an original MovieBox-inspired interface. It uses TMDB only for movie metadata/posters and provider discovery. It does not copy MovieBox private APIs, database, streams, or copyrighted assets.

## Setup
1. Create a TMDB API Read Access Token from your TMDB account.
2. Open **Movies** in AsgharDownloader.
3. Tap the key/settings icon and paste the token.
4. The token is stored only in the app's private preferences.

The catalog supports search and language filters. Watch-provider data is obtained from TMDB/JustWatch and opens the provider link returned for the selected region. TMDB does not provide direct movie-file download URLs through this endpoint, so the app does not pretend that a provider link is a downloadable movie file.

Downloaded files produced by the existing downloader remain under the AsgharDownloader download structure. A dedicated `Movies` directory helper is present for authorized movie-file integrations.
