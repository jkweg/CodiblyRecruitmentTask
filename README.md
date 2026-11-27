# Planer ładowania

Aplikacja do zadania rekrutacyjnego Codibly IT Academy. Pozwala porównać miks energii Wielkiej Brytanii, znaleźć ciągłe okno ładowania z najwyższym udziałem czystej energii oraz zarezerwować ładowarkę. W kalendarzu widać zajęte przedziały, a własne rezerwacje można anulować.

- [Otwórz aplikację](https://codibly-recruitment-task-beta.vercel.app/)
- [Repozytorium na GitHubie](https://github.com/jkweg/CodiblyRecruitmentTask)

## Architektura

| Katalog lub usługa | Rola |
|---|---|
| `frontend/` | React 19, TypeScript i Vite. Frontend działa na Vercelu. |
| `backend/` | Java 21, Spring Boot 4.2, Gradle, Spring Data JPA i Flyway. Backend działa w kontenerze Docker. [API na Renderze](https://codiblyrecruitmenttask.onrender.com/api/chargers) |
| Supabase | PostgreSQL z ładowarkami i rezerwacjami. Backend łączy się z bazą przez Session Pooler. |

Przeglądarka korzysta ze względnych adresów `/api`. Lokalnie Vite przekazuje je do Spring Boota na porcie `8080`. [Na Vercelu](frontend/vercel.json) te same ścieżki prowadzą do Rendera.

27 listopada 2026 r. publiczna strona oraz `/api/chargers`, `/api/energy/mix` i `/api/energy/best-window?hours=1` przez Vercel zwróciły HTTP `200`. Pełny scenariusz rezerwacji z dwiema sesjami oraz zachowanie klienta energii podczas timeoutu sprawdzono lokalnie. Próby po starcie Rendera i publiczny przepływ rezerwacji opisano niżej.

## Uruchomienie lokalne

Potrzebujesz Git, JDK 21 z ustawionym `JAVA_HOME`, Node.js 24 LTS z npm oraz działającego lokalnego PostgreSQL. Instrukcję sprawdzono na Node.js 24.12, npm 11.6 i PostgreSQL 18. Gradle 9.9.2 pobierze dołączony Wrapper. Do sprawdzenia danych energetycznych potrzebne jest połączenie z publicznym Carbon Intensity API.

```bash
git clone https://github.com/jkweg/CodiblyRecruitmentTask.git
cd CodiblyRecruitmentTask
```

### 1. Przygotuj PostgreSQL

Na lokalnym serwerze uruchom klienta `psql` jako użytkownik administracyjny, np. `psql -h 127.0.0.1 -U postgres -d postgres -W`. Na nowej instancji wykonaj poniższe polecenia SQL. Jeżeli masz już odpowiednią rolę, możesz jej użyć z osobnymi bazami aplikacji i testów.

```sql
CREATE ROLE codibly_local LOGIN PASSWORD 'local-dev-only';
CREATE DATABASE codibly OWNER codibly_local;
CREATE DATABASE codibly_test OWNER codibly_local;
```

### 2. Uruchom backend

Utwórz `backend/.env` obok `build.gradle`. Spring importuje go przy starcie w formacie Java Properties.

```properties
spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/codibly
spring.datasource.username=codibly_local
spring.datasource.password=local-dev-only
```

To przykładowe dane wyłącznie dla lokalnej bazy. Plik `.env` jest ignorowany przez Git. W środowisku uruchomieniowym te same ustawienia można przekazać przez `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME` i `SPRING_DATASOURCE_PASSWORD`. Domyślny port HTTP to `8080`. W konfiguracji lokalnego proxy Vite jest wpisany właśnie ten port.

W pierwszym terminalu przejdź z katalogu głównego do `backend/` i uruchom:

```powershell
cd backend
.\gradlew.bat bootRun
```

Na macOS i Linuksie użyj `sh ./gradlew bootRun`. Przy pierwszym starcie w pustej bazie Flyway uruchomi migracje. V1 utworzy tabelę ładowarek i doda trzy przykładowe stacje, a V2 utworzy tabelę rezerwacji. Hibernate sprawdzi schemat przez `ddl-auto=validate`. [Migracje Flyway](backend/src/main/resources/db/migration)

### 3. Uruchom frontend

Zostaw backend włączony. W drugim terminalu, z katalogu głównego repozytorium, wykonaj:

```bash
cd frontend
npm ci
npm run dev
```

Otwórz adres z konsoli Vite, zwykle [http://localhost:5173](http://localhost:5173). W sekcji rezerwacji powinny być dostępne trzy przykładowe ładowarki. Gdy dostawca prognozy odpowiada, sekcja energii pokaże miks i pozwoli wyszukać okno. W PowerShellu można użyć `npm.cmd`, jeżeli system blokuje `npm.ps1`.

## Testy i build

Każdy z poniższych bloków poleceń zacznij w katalogu głównym repozytorium.

Backendowe testy kontekstu i rezerwacji uruchamiają profil `test`. Flyway przygotowuje schemat w oddzielnej bazie `codibly_test` na `127.0.0.1`. W terminalu testowym ustaw parametry tej bazy. Pozostaw w nim nieustawione aplikacyjne zmienne `SPRING_DATASOURCE_*` i `SPRING_FLYWAY_*`, ponieważ mają pierwszeństwo przed profilem testowym. [Konfiguracja testowa](backend/src/test/resources/application-test.properties)

```powershell
cd backend
$env:CODIBLY_TEST_DB_PORT = '5432'
$env:CODIBLY_TEST_DB_USER = 'codibly_local'
$env:CODIBLY_TEST_DB_PASSWORD = 'local-dev-only'
.\gradlew.bat test
```

```bash
cd backend
CODIBLY_TEST_DB_PORT=5432 CODIBLY_TEST_DB_USER=codibly_local CODIBLY_TEST_DB_PASSWORD=local-dev-only sh ./gradlew test
```

Frontend po instalacji zależności testuje się niezależnie od serwera:

```bash
cd frontend
npm test
npm run lint
npm run build
```

Dobór testów wynika z ryzyka dla użytkownika:

- **Rezerwacje i PostgreSQL.** Dwie równoczesne transakcje próbują zająć ten sam termin. Test potwierdza odpowiedzi `201` i `409` oraz jeden zapis w bazie. Osobne przypadki sprawdzają izolację właścicieli, zwolnienie terminu po anulowaniu oraz stykające się przedziały.
- **Miks i okna.** Kontrolowane dane sprawdzają luki, duplikaty, niepełne dni, remis, okna przez północ i zmianę czasu. Zapobiega to sugerowaniu terminu, dla którego brakuje części prognozy.
- **Dostawca prognozy.** Lokalny serwer HTTP symuluje timeout, sukces po ponowieniu i utrzymującą się awarię. Testy sprawdzają budżet prób, brak ponowienia dla błędnego HTTP lub JSON-a i surową ścieżkę zapytania.
- **Interfejs.** Vitest i Testing Library sprawdzają wybór dnia i legendę, puste dane, błędy, spóźnione odpowiedzi oraz zachowanie formularza po konflikcie. Kontrolowane odpowiedzi API zapewniają powtarzalność.

## Decyzje projektowe

- **Prognoza i czas.** Miks obejmuje dziś, jutro i pojutrze. „Dwa kolejne dni” dla najczystszego okna oznaczają jutro i pojutrze. Granice dni i czas w interfejsie używają `Europe/London`. Backend porównuje konkretne momenty w UTC, więc okna mogą przechodzić przez północ i zmianę czasu. Jeżeli godzina wpisana w formularzu wypada w luce lub powtarza się przy zmianie czasu, interfejs prosi o wybór innej godziny.
- **Niepełne dane.** Średnie dzienne powstają z dostępnych poprawnych slotów. UI pokazuje pokrycie i pomija puste dni w rankingu. Wyszukiwanie dla 1–6 h wymaga odpowiednio 2–12 przylegających slotów. Brak pełnego okna zwraca `204`, a niedostępność źródła zwraca `502`. Udział czystej energii obejmuje biomasę, energię jądrową, wodę, wiatr i słońce.
- **Awaria dostawcy.** Klient ma 3 s na zestawienie nowego połączenia i 5 s na całą próbę. Rozpoznany timeout pozwala na jedno ponowienie, z budżetem do 10 s oczekiwania na transport. Log zapisuje operację, numer próby, czas, kategorię przyczyny i ewentualny status HTTP.
- **Kolizje rezerwacji.** PostgreSQL blokuje wiersz wybranej ładowarki w transakcji obejmującej sprawdzenie kolizji i zapis. Przedziały `[start, end)` pozwalają rozpocząć rezerwację dokładnie w chwili zakończenia poprzedniej. Różne ładowarki mają niezależne blokady.
- **Anonimowa własność.** Losowy identyfikator właściciela znajduje się w ciasteczku `HttpOnly`, z flagą `Secure` na HTTPS. Lista i anulowanie są ograniczone do tej sesji. Kalendarz ujawnia wyłącznie zajęte przedziały. Usunięcie ciasteczka oznacza utratę dostępu do anonimowego zestawu rezerwacji.

### Dodatki

Interaktywny wykres pierścieniowy z tekstową legendą, responsywny panel planera oraz widok kalendarza na jeden lub siedem dni z oznaczeniem przedziałów przechodzących przez północ.

## Sprawdzenia po wdrożeniu

1. Tuż po starcie Rendera wywołaj `/api/energy/mix` i `/api/energy/best-window?hours=1`. Przy `502` sprawdź wpis `operation=generation` z tego czasu i pola `cause`, `rootType`, `elapsedMs` i `retry`.
2. Z publicznego adresu aplikacji utwórz rezerwację i sprawdź, czy jest widoczna po odświeżeniu. Zweryfikuj w przeglądarce atrybuty `Secure; HttpOnly` ciasteczka właściciela. W drugiej sesji spróbuj zająć ten sam termin, żeby potwierdzić konflikt. Anuluj w pierwszej sesji i upewnij się, że termin jest ponownie dostępny.
