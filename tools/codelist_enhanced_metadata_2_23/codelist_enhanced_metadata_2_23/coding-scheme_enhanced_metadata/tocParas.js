/* These are the parameters to define the appearance of the ToC. */
var
	showNumbers = true, 		// display the ordering strings: yes=true | no=false
	backColor = "#336699",		// background color of the ToC 
	normalColor = "#FFFFFF",	// text color of the ToC headlines
	lastVisitColor = "#FFFFBB",	// text color of the line last visited
	currentColor = "#FFFF00",	// text color of the actual line just clicked on
	titleColor = "#FFFFFF",		// text color of the title "Table of Contents"
	mLevel = 1,					// number of levels minus 1 the headlines of which are presentet with large and bold fonts   
	textSizes = new Array(1, 0.7, 0.6, 0.7, 0.6),			// font-size factors for: [0] the title "Table of Contents", [1] larger and bold fonts [2] smaller fonts if MS Internet Explorer [3] larger and bold fonts [4] smaller fonts if Netscape Navigator.
	fontTitle = "Helvetica,Arial", // font-family of the title "Table of Contents"
	fontLines = "Helvetica,Arial", // font-family of the headlines
	tocScroll=true,				// Automatic scrolling of the ToC frame (true) or not(false)
	tocBehaviour = new Array(1,0), // Indicates how the ToC shall change when clicking in the heading symbol (1st arg.) resp. in the heading text (2nd arg). Arg's meaning: 0 = No change, 1 = ToC changes with automatic collapsing, 2 = ToC changes with no automatic collapsing.
	tocLinks = new Array(1,0);	// Indicates wether the content's location shall be changed when clicking in the heading symbol (1st arg.) resp. in the heading text (2nd arg). Arg's meaning: 1 = No, 0 = Yes. 
	