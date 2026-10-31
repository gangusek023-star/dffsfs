# TurboCPU ⚡

Aplikacja na Androida do sterowania procesorem i ochrony wybranych aplikacji przed zamknięciem.

## Wymagania
- Android 8.0+
- **Root** (Magisk / KernelSU / APatch). Bez roota aplikacja pokazuje tylko odczyty.
- Taktowanie **powyżej fabrycznego maksimum** wymaga custom kernela z odblokowanymi wyższymi częstotliwościami. TurboCPU wykorzysta je automatycznie, jeśli kernel je udostępnia.

## Funkcje
- Podgląd taktowania każdego rdzenia, governora i temperatury na żywo
- Profile: Oszczędny, Zrównoważony, Wydajność, Turbo (wszystkie rdzenie na maks.)
- Suwak limitu taktowania (50–100%) i bezpiecznik temperatury (60–90°C)
- **Not Crash**: wybrane aplikacje dostają `oom_score_adj = -1000`, więc system ich nie zabija przy braku RAM-u; po nagłym zamknięciu są uruchamiane ponownie (maks. 5 prób, żeby uniknąć pętli)
- **Boost per aplikacja**: Turbo włącza się, gdy wybrana gra jest na ekranie, i wyłącza po wyjściu
- Ochrona startuje automatycznie po restarcie telefonu

## Jak zbudować APK
**Bez komputera (GitHub):** wrzuć ten folder do nowego repozytorium na GitHubie → zakładka *Actions* → *Zbuduj APK* → po ~5 min pobierz `TurboCPU-apk` z sekcji *Artifacts*.

**Android Studio:** File → Open → wybierz folder `TurboCPU` → Build → Build APK.

## Uwaga
Ustawienia procesora wracają do domyślnych po restarcie telefonu. Jeśli telefon zacznie się wieszać lub restartować na profilu Turbo, obniż suwak limitu taktowania.
