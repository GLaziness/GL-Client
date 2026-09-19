package mindustry.client.ui;

import arc.*;

public enum CursednessLevel {
	NORMAL,
	UHH,
	OHNO,
	CURSED,
	WWWHHHHHYYYY,
	/** GL: armed units fly in, shoot once at the cursor and fly away. */
	PEWPEW;
	//Warning: do not change the order.
	public static CursednessLevel fromInteger(int x) {
		return switch(x) {
			// GL: PEW PEW is second on the slider and the default (1), the rest moved one step up
			case 0 -> NORMAL;
			case 1 -> PEWPEW;
			case 2 -> UHH;
			case 3 -> OHNO;
			case 4 -> CURSED;
			case 5 -> WWWHHHHHYYYY;
			default -> NORMAL;
		};
	}
	public static CursednessLevel get(){
		return Core.settings != null ? fromInteger(Core.settings.getInt("cursednesslevel", 1)) : CursednessLevel.NORMAL;
	}
	public static boolean atLeast(CursednessLevel level){
		return get().ordinal() >= level.ordinal();
	}
}
