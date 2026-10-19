# Yak UI source reuse

Source: [weifuwan/yak-ops](https://github.com/weifuwan/yak-ops/tree/85338827bdbb745eaa6a017031640674a298a4f9), commit `85338827bdbb745eaa6a017031640674a298a4f9`, Apache-2.0. The original license is retained in `LICENSE`.

`source.json` records each imported file and the SHA-256 of its UTF-8 text with LF line endings and one final newline. The selected button, input, card, empty, spinner, dialog and table entry points retain their actual transitive source dependencies. Table pagination is disabled for the Native draft list because the server owns cursor pagination; its existing next-page action remains authoritative.

The copied component behavior remains unchanged; only line endings and the final newline are normalized. OntoForge adaptations live outside this directory: `shared/theme/ThemeProvider.tsx` adapts Yak's browser theme provider with safe storage access, system-theme changes and cross-tab updates; `AppShell.tsx` adapts its layout structure to existing OntoForge routes and identity. Theme colors and the existing workspace CSS consume shared semantic tokens. No Yak product branding assets, login endpoint, API response wrapper, workspace permission or model semantics are introduced by this UI slice.

Dependencies remain in the existing single npm project. Base UI and CVA match the selected source declarations; Tailwind 4.3.3 supports this project's Vite 8. The production app stays on its existing React 19/TypeScript 7/Vite 8 stack.
