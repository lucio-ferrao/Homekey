# Keyarchy
Keyarchy is an Android keyboard that is also the home screen: a fork of [HeliBoard](https://github.com/HeliBorg/HeliBoard) where the suggestion strip doubles as an app launcher, and the home screen is a list of apps, chats, notifications and events searched from the keyboard.
It was called Homekey until November 2026.
Like HeliBoard, it does not use the internet permission: everything stays on the phone (dictation goes through the speech recognizer of the system).

It is a personal project, made for one phone (a Pixel 8 Pro) and Portuguese (Portugal). Debug APKs are in the [releases](https://github.com/lucio-ferrao/Keyarchy/releases).

## Table of Contents

- [Launcher mode](#launcher-mode)
- [Home screen](#home-screen)
- [Keyboard](#keyboard)
- [Setup](#setup)
- [Building](#building)
- [License](#license)
- [Credits](#credits)

# Launcher mode
A double tap on the space bar, or the Keyarchy icon at the left of the suggestion strip, turns the suggestion strip into a search: the keys type into it instead of the app, and another double tap, the icon (now turned a quarter) or a tap in the app leaves it. A long press on the icon goes to the home screen; the settings are an entry of the list ("Keyarchy Settings").

- Apps of the main profile, the work profile and the private space; an app of a locked private space unlocks it first and opens once it is unlocked
- Monochrome icons in the text color (themed layer, or the icon reduced to two shades with its background removed), cached between sessions
- The list is saved and read when the keyboard starts, so it shows at once also after a restart, and is refreshed in the background
- Names and tags, imported from the [KISS launcher](https://kisslauncher.com/) export or set on the home screen, searched like the app names
- Order by recent use (without a search, notifications among the rest by when they came), and the result last chosen for the same text goes first
- Chats of starred contacts with WhatsApp (the star on the contact that has WhatsApp's entry, linked if Android split it): WhatsApp, or Element X (or MatriX, its fork) with the room or person set for the contact; the message box is tapped once the chat opens, so the keyboard stays
- Notifications (not the silent ones), searchable by their text; a one-to-one message from a starred contact goes into the entry of that person and moves it forward, as does typing in their chat in WhatsApp, Element X or MatriX (MatriX tells the keyboard its room in `org.ferrao.homekey.room`; for the others the name of the chat is read by the accessibility service); in the strip a notification or a message is dismissed by sliding it to the right (apps, people and shortcuts leave the recent ones only on the home screen)
- What players are playing, in the place of the player app
- Today's events of the calendar, coming forward as they get near, with the name of their calendar (or its address, shortened) under them on the home screen; the calendars in it are chosen in Settings › Calendars
- Shortcuts of the apps turned on in Settings › App shortcuts (all off at first; each app with its shortcuts listed and a switch) and the ones pinned to Keyarchy ("add to home screen"); the shortcuts of conversations with a starred contact (WhatsApp, Messages) are left out, as the contact has its entry; any shortcut can be removed from the list; the panes of Ecto come forward by their agent: now while it works or waits for input, else from when it stopped, with the icon of Ecto, which becomes the spinner of Claude Code (· ✢ ✳ ✶ ✻) while the agent works, and under it only "blocked" or "done"
- The typed text stays where the names of the results start, also when nothing matches
- Typed or pasted web addresses, with the icon of the app that opens them
- Patterns: a phone number gives Call, "10 min" a timer, "7h15" an alarm
- Shortcuts for starred contacts: `d`/`drive`/`go name` (directions in Google Maps), `sms name`, `c`/`call`/`liga`/`ligar name`
- `s`/`search text`: a web search in the default browser, with its search engine
- `ssh name`: [Ecto](https://github.com/lucio-ferrao/termux-ghostty) opened connected to that saved server; `ssh [name] -L 3000`, `-R 5555`, `-KL 3000`, `-KR 5555`: a port forward asked of Ecto (ssh syntax, a port alone meaning the same port on localhost), its answer shown in the strip
- Enter opens the first result, or runs the sentence when it is one of the spoken commands below
- Dictation with a long press on the space bar, and spoken commands in Portuguese ("abre o Spotify", "liga à Ema", "manda mensagem à Marta a dizer que chego às 8")

# Home screen
When Keyarchy is chosen as the home app, the home screen is the launcher mode as a vertical list above a search field (with an arrow at its left), on black, with the keyboard always open.

- Tags, or the latest message of a person, under each name
- A long press opens a panel: name, tags, WhatsApp or Element for a person, uninstall an app, remove a shortcut
- Swiping a notification, a message or an event aside dismisses it (an event only from the list); swiping an app, a person without a message or a shortcut takes it out of the recent ones, until opened again or, for a person or a shortcut, a new message or change
- The home gesture or back clears the search
- Word suggestions and autocorrection in the search field, which also know the names and tags of the list, so a name typed with a wrong letter is corrected ("spotifu" gives "Spotify")

# Keyboard
All the features of HeliBoard (see its [wiki](https://github.com/HeliBorg/HeliBoard/wiki)), with these changes:

- A Portuguese (Portugal) main dictionary without Brazilian spellings and with accent misspellings turned into corrections (see below)
- Emojis in the suggestion strip, with the keyboard still shown (emoji key, or emoji in the alternatives of Enter): the recent ones first, and typing filters them by their names and keywords in English and Portuguese, from the [CLDR](https://cldr.unicode.org/) annotations (`tools/emoji-nomes.py` makes `assets/emoji/nomes.txt`); the emoji grid of HeliBoard is gone
- Emoji while typing `:` and what follows, as in [wassup](https://github.com/lucio-ferrao/wassup): its smileys first (`:)`, `:*`), then the emoji with a name in Portuguese without accents or in English that starts so (`:love`, `:kiss`, `:beijinho`), and the CLDR names when those have none; picking one replaces the `:text`, and typing on that no longer fits closes them (Enter stays Enter). Also with a coding agent in Ecto; not in a shell or a full-screen program, passwords, web or email addresses, and no emoji dictionary is needed
- Notifications arriving while typing show in the suggestion strip until the next key or 10 s; a tap opens them
- Symbols: `/` as the hint and long press of the period; emoji next to Enter in its alternatives, before the new line where Enter sends
- Enter without the colored circle, like the other functional keys
- Ctrl by sliding from shift: shift slid to c sends Ctrl+C (also Tab, arrows, numbers), with "Ctrl" on the shift key while sliding; ignored in the launcher and emoji modes
- No toolbar: the Keyarchy icon at the left of the suggestion strip opens the apps instead, and the toolbar of HeliBoard (its keys, pinned keys and settings) is gone; the clipboard history keeps its own row of keys
- A tap on the left third of the period next to the space bar counts as space, and "word.word" with both words in the dictionary becomes "word word"
- Terminal fields of [Ecto](https://github.com/lucio-ferrao/termux-ghostty), an SSH terminal, say what runs in them: Portuguese prose for a coding agent; for a shell no corrections, capitals or automatic spaces, and suggestions of the server's commands at the start of a line, of the files and folders of the current folder after it (a folder is picked without a space) and of the words typed before; keys only for a full-screen program like vim
- Enter sends in Element X and MatriX, and taps the send button in Claude (through the accessibility service); suggestions and corrections in Instagram messages

<ul>
  <li>Add dictionaries for suggestions and spell check</li>
  <ul>
    <li>build your own, or get them  <a href="https://codeberg.org/Helium314/aosp-dictionaries#dictionaries">here</a> (quality may vary)</li>
    <li>additional dictionaries for emojis or scientific symbols can be used to provide suggestions (similar to "emoji search")</li>
    <li>the built-in Portuguese (Portugal) dictionary is made from the <a href="https://codeberg.org/Helium314/aosp-dictionaries">experimental pt_PT word list</a> (built from the <a href="https://wortschatz.uni-leipzig.de/en/download/">Leipzig Corpora Collection</a>, <a href="https://creativecommons.org/licenses/by/4.0/">CC BY 4.0</a>), with Brazilian spellings removed or demoted and misspellings of accents turned into corrections</li>
    <li>note that for Korean layouts, suggestions only work using <a href="https://github.com/openboard-team/openboard/commit/83fca9533c03b9fecc009fc632577226bbd6301f">this dictionary</a>, the tools in the dictionary repository are not able to create working dictionaries</li>
  </ul>
  <li>Customize keyboard themes (style, colors and background image)</li>
  <li>Emoji search (inline, after a colon)</li>
  <ul>
    <li>can follow the system's day/night setting on Android 10+ (and on some versions of Android 9)</li>
    <li>can follow dynamic colors for Android 12+</li>
  </ul>
  <li>Customize keyboard <a href="https://github.com/HeliBorg/HeliBoard/blob/main/layouts.md">layouts</a> (only available when disabling <i>use system languages</i>)</li>
  <li>Customize special layouts, like symbols, number,  or functional key layout</li>
  <li>Multilingual typing</li>
  <li>Glide typing (<i>only with closed source library</i> ☹️)</li>
  <ul>
    <li>library not included in the app, as there is no compatible open source library available</li>
    <li>can be extracted from GApps packages ("<i>swypelibs</i>"), or downloaded <a href="https://github.com/erkserkserks/openboard/tree/46fdf2b550035ca69299ce312fa158e7ade36967/app/src/main/jniLibs">here</a> (click on the file and then "raw" or the tiny download button)</li>
  </ul>
  <li>Clipboard history</li>
  <li>One-handed mode</li>
  <li>Split keyboard</li>
  <li>Number pad</li>
  <li>Backup and restore your settings and learned word / history data</li>
</ul>

# Setup
Keyarchy needs a few things Android asks for separately:

- Choose it as keyboard, and as home app (Settings › Apps › Default apps)
- Notification access, for notifications (in the list and in the suggestion strip) and players
- The accessibility service "Keyarchy chats", to tap the message box of a chat, the send button of Claude, and to leave the launcher and emoji modes on a tap in the app
- Permissions for contacts (starred contacts), calendar (events) and microphone (dictation)
- For glide typing, the closed source library, as in HeliBoard

# Building
```
./gradlew assembleDebug
```
The APK is `app/build/outputs/apk/debug/Keyarchy_<version>-debug.apk`, with the package `org.ferrao.homekey.debug` (`org.ferrao.homekey` for release builds).

# License

Keyarchy, like HeliBoard (a fork of OpenBoard), is licensed under GNU General Public License v3.0.

 > Permissions of this strong copyleft license are conditioned on making available complete source code of licensed works and modifications, which include larger works using a licensed work, under the same license. Copyright and license notices must be preserved. Contributors provide an express grant of patent rights.

See repo's [LICENSE](/LICENSE) file.

Since the app is based on Apache 2.0 licensed AOSP Keyboard, an [Apache 2.0](LICENSE-Apache-2.0) license file is provided.
The names of the emojis (`assets/emoji/nomes.txt`) come from the CLDR annotations, under the [Unicode License v3](https://www.unicode.org/license.txt).
The icon of Keyarchy (a crown of three `^`, as a terminal writes Ctrl, over the `_` of the cursor) is its own; the icon of HeliBoard, no longer used, was licensed under [Creative Commons BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/) ([license file](LICENSE-CC-BY-SA-4.0)).

# Credits
- [HeliBoard](https://github.com/HeliBorg/HeliBoard), which Keyarchy is a fork of, and its [contributors](https://github.com/HeliBorg/HeliBoard/graphs/contributors)
- [OpenBoard](https://github.com/openboard-team/openboard)
- [AOSP Keyboard](https://android.googlesource.com/platform/packages/inputmethods/LatinIME/)
- [LineageOS](https://review.lineageos.org/admin/repos/LineageOS/android_packages_inputmethods_LatinIME)
- [Simple Keyboard](https://github.com/rkkr/simple-keyboard)
- [Indic Keyboard](https://gitlab.com/indicproject/indic-keyboard)
- [FlorisBoard](https://github.com/florisboard/florisboard/)

## Funding of HeliBoard

HeliBoard is funded through [NGI Mobifree Fund](https://nlnet.nl/mobifree), a fund established by [NLnet](https://nlnet.nl) with financial support from the European Commission's [Next Generation Internet](https://ngi.eu) program. Learn more at the [NLnet project page](https://nlnet.nl/project/GestureTyping).

[<img src="https://nlnet.nl/logo/banner.png" alt="NLnet foundation logo" width="20%" />](https://nlnet.nl)

Further HeliBoard benefits from donations provided by many users (thank you all!).
