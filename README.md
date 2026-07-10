# Odczyt DDD

Prosta aplikacja Android dla kierowcy:

1. podłącz czytnik kart USB-C/OTG,
2. włóż kartę kierowcy,
3. naciśnij **ODCZYTAJ KARTĘ**,
4. po sukcesie naciśnij **WYŚLIJ PLIK DDD**.

## Założenia interfejsu

- jeden ekran,
- dwa duże przyciski,
- duże komunikaty,
- brak logowania,
- brak reklam,
- brak internetu,
- brak konfiguracji serwera,
- plik DDD jest zapisywany lokalnie w pamięci aplikacji,
- wysyłanie przez systemowe menu Androida (e-mail, WhatsApp, Dysk itd.).

## Obsługiwany sprzęt

Aplikacja wyszukuje standardowy interfejs USB Smart Card / CCID (klasa USB `0x0B`) z endpointami Bulk IN/OUT. Telefon musi obsługiwać USB Host/OTG.

## Stan projektu

To **prototyp do testów sprzętowych**, nie wersja produkcyjna. Kod implementuje:

- USB Host na Androidzie,
- minimalny transport CCID,
- zasilenie karty i wymianę APDU,
- wykrywanie aplikacji tachografowej Gen1 i Gen2,
- odczyt EF-ów karty,
- tworzenie DDD jako bloków TLV,
- pobieranie podpisów plików,
- próbę aktualizacji `EF Card_Download (050E)`,
- lokalny zapis i udostępnianie pliku.

Przed użyciem w firmie trzeba sprawdzić plik DDD w niezależnym walidatorze i przetestować aplikację na docelowym czytniku oraz kilku typach kart kierowcy.

## Ograniczenia prototypu

- nie był testowany na fizycznej karcie ani konkretnym czytniku w tym środowisku,
- minimalny transport CCID może wymagać dopasowania do nietypowego czytnika,
- odczyt pliku używa krótkiego `READ BINARY` i zakresu offsetu do `0x7FFF`,
- nie ma jeszcze automatycznej walidacji kryptograficznej gotowego DDD,
- nazwa pliku jest czasowa (`KARTA_yyyyMMdd_HHmmss.DDD`), bez numeru karty/kierowcy.

## Prywatność

Projekt nie deklaruje uprawnienia INTERNET. Pliki pozostają lokalnie do momentu ręcznego użycia przycisku **WYŚLIJ PLIK DDD**.
