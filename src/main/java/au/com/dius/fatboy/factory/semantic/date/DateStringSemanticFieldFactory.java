package au.com.dius.fatboy.factory.semantic.date;

import au.com.dius.fatboy.factory.config.FactoryHint;
import au.com.dius.fatboy.factory.semantic.SemanticFieldFactory;
import au.com.dius.fatboy.factory.semantic.SemanticFieldFactoryHint;
import org.joda.time.DateTime;
import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.ISODateTimeFormat;

import java.lang.reflect.Field;
import java.util.regex.Pattern;

public class DateStringSemanticFieldFactory extends FactoryHint implements SemanticFieldFactory<String> {
    private SemanticFieldFactoryHint<String> fieldMatcher;

    public DateStringSemanticFieldFactory() {
        this(ISODateTimeFormat.date());
    }

    public DateStringSemanticFieldFactory(DateTimeFormatter dateTimeFormatter) {
        fieldMatcher = new SemanticFieldFactoryHint<>(String.class);
        fieldMatcher.addFieldMatcher(Pattern.compile("(^date$)|(.*Date(?!(time|Time))(\\p{Upper}|$).*)"), () -> dateTimeFormatter.print(new DateTime()));
    }

    public boolean supports(Field field) {
        return fieldMatcher.supports(field);
    }

    public String create(Field field) {
        return fieldMatcher.create(field);
    }
}