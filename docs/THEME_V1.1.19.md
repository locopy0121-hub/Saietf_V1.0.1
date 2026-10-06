# SaiETF Theme Contract — V1.1.19

SaiETF now has a persistent three-state theme preference: Follow Android system, Light, and Dark.

The mode is presentation-only. Finance Core and Market Update Center Core remain locked and unchanged.

All shared SaiTheme tokens provide light and dark values. MainActivity resolves the stored mode before constructing runtime views, applies matching status/navigation bar colors and system-icon contrast, and recreates the activity after the user changes the preference.

Resetting interface settings returns theme mode to Follow Android system.
