package com.coderabbit.donutsavings.core;

/** Turns what a server prints into plain text the patterns can match. */
public final class Text {
	/** Small-caps letters many servers use in menus and scoreboards ("ᴍᴏɴᴇʏ"), mapped to ASCII. */
	private static final String SMALL = "ᴀʙᴄᴅᴇꜰɢʜɪᴊᴋʟᴍɴᴏᴘǫʀꜱᴛᴜᴠᴡʏᴢ";
	private static final String PLAIN = "abcdefghijklmnopqrstuvwyz";

	private Text() {
	}

	/** Drops § formatting codes, maps small caps to ASCII, folds odd spaces and trims. */
	public static String normalize(String s) {
		if (s == null) {
			return "";
		}

		StringBuilder b = new StringBuilder(s.length());

		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);

			if (c == '§') {
				i++; // skip the code character too
				continue;
			}

			int small = SMALL.indexOf(c);

			if (small >= 0) {
				b.append(PLAIN.charAt(small));
			} else if (c == ' ' || c == ' ' || c == ' ' || c == '\t') {
				b.append(' ');
			} else if (c == '＄') {
				b.append('$'); // full-width dollar sign
			} else {
				b.append(c);
			}
		}

		return b.toString().replaceAll(" {2,}", " ").trim();
	}
}
