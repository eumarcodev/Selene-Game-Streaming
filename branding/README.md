# Branding

Selene icon: the moon. Silver ring, crescent moon, sapphire core and waves arriving from the left that cool from orange
to cyan: the client receives the stream and reflects it. It pairs with the Helios icon (the sun, which emits the
stream), kept in [Helios-Server/branding](https://github.com/eumarcodev/Helios-Server/tree/develop/branding).

Palette: silver `#D9DEE8`, cyan `#5CE1FF`, sapphire `#2F5BFF`, amethyst `#8B5CF6`, obsidian background `#0A0B0F`.
Helios side: gold `#F5B82E`, orange `#FF6B1A`, ruby `#E0263B`.

## Files

| Path | What |
|---|---|
| `icon-core.cjs` | Shared building blocks (palette, ring, gem, waves). **Identical copy in Helios**: change both. |
| `generate.cjs` | Writes the Selene SVGs and the TV channel vector drawable to `svg/` |
| `export.py` | Renders `svg/` with headless Chrome/Edge and writes every launcher/banner raster of this repo (needs Pillow) |
| `svg/` | Generated Selene SVGs |
| `selene-icon.png` | README icon |
| `helios-selene-lockup.svg`, `helios-selene.png` | The pair (README banner). Same files in both repos. |

## Regenerating

```bash
node branding/generate.cjs
python branding/export.py
```

| SVG | Used for |
|---|---|
| `selene-foreground` / `selene-background` / `selene-monochrome` | adaptive launcher layers (`mipmap-*/ic_launcher_*`) |
| `selene-icon-rounded` | legacy launcher `ic_launcher.png` |
| `selene-icon` | `ic_launcher-web.png` (Play Store, 512 px) |
| `selene-monochrome-small` | notification icon `drawable/app_icon.png` |
| `selene-monochrome.xml` | TV channel logo `drawable/ic_lime_layer.xml` |
| `selene-banner-tv` / `selene-banner-ouya` | `atv_banner.png`, `ouya_icon.png` |
