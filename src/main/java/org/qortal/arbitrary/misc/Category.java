package org.qortal.arbitrary.misc;

public enum Category {
    ART_AND_DESIGN("Art and Design"),
    AUTOMOTIVE("Automotive"),
    BEAUTY("Beauty"),
    BOOKS("Books and Reference"),
    BUSINESS("Business"),
    COMMUNICATIONS("Communications"),
    CRYPTOCURRENCY("Cryptocurrency and Blockchain"),
    DATING("Dating"),
    ENTERTAINMENT("Entertainment"),
    EVENTS("Events"),
    FASHION("Fashion"),
    FINANCE("Finance"),
    FOOD("Food and Drink"),
    GAMING("Gaming"),
    GEOGRAPHY("Geography"),
    HEALTH("Health"),
    HISTORY("History"),
    HOME("Home"),
    KNOWLEDGE("Knowledge Share"),
    LANGUAGE("Language"),
    LIFESTYLE("Lifestyle"),
    MANUFACTURING("Manufacturing"),
    MAPS("Maps and Navigation"),
    MEDICAL("Medical"),
    MUSIC("Music"),
    NEWS("News"),
    OTHER("Other"),
    PERSONALIZATION("Personalization"),
    PETS("Pets"),
    PHILOSOPHY("Philosophy"),
    PHOTOGRAPHY("Photography"),
    POLITICS("Politics"),
    PRODUCTIVITY("Productivity"),
    PSYCHOLOGY("Psychology"),
    QORTAL("Qortal"),
    RELIGION("Religion"),
    SCIENCE("Science"),
    SERVICES("Services"),
    SHOPPING("Shopping"),
    SOCIAL("Social"),
    SOFTWARE("Software"),
    SPORTS("Sports"),
    TECHNOLOGY("Technology"),
    TOOLS("Tools"),
    TRAVEL("Travel"),
    VIDEO("Video"),
    WEATHER("Weather");

    private final String name;

    Category(String name) {
        this.name = name;
    }

    public String getName() {
        return this.name;
    }

}
