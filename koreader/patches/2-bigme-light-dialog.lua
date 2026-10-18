--[[ InKognito / Bigme B1051C Pro: KOReader's light menu opens its own front-light widget (intensity + warmth,
driven by 1-bigme-eink.lua through E-Ink Control) instead of the native Android dialog, which only moves the
window brightness. Undo: delete this file. ]]
local Device = require("device")
if not Device:isAndroid() then return end
local UIManager = require("ui/uimanager")

function Device:showLightDialog()
    UIManager:show(require("ui/widget/frontlightwidget"):new{})
end
