-- /storage/emulated/0/koreader/settings.reader.lua
-- InKognito alpha 1: ROM defaults for KOReader v2026.07.1 (org.koreader.launcher) on the Bigme B1051C Pro.
-- ROM-level keys only. KOReader and the ZenOS plugin add everything else on the first start
-- (KOReader rewrites this file on its first save; these comments are then dropped).
--   color_rendering      Kaleido color panel: render color (also when the launcher's color detection says no)
--   full_refresh_count   KOReader's "full refresh every N pages" (its default, made explicit): each full refresh
--                        reaches the panel as one settled GC16 through patches/1-bigme-eink.lua -> E-Ink Control;
--                        E-Ink Control's KOReader profile sets the shim's own full-refresh-every-N to 0
--   inertial_scroll      no kinetic scrolling on e-ink (safety net if the e-ink patch is not loaded)
--   last_migration_date  CURRENT_MIGRATION_DATE of v2026.07.1: this file is already in that version's format
--   plugins_disabled     empty: zenos.koplugin and derainbowify.koplugin load (bookshelf.koplugin is not shipped)
return {
    ["color_rendering"] = true,
    ["full_refresh_count"] = 6,
    ["inertial_scroll"] = false,
    ["last_migration_date"] = 20260623,
    ["plugins_disabled"] = {},
}
