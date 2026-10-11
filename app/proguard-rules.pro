# Enum names are stored (saved state, Pronote caches) and passed in intents
# (widgets, shortcuts, tile): R8 must not rename them.
-keepclassmembers enum fr.sallio.** { *; }
