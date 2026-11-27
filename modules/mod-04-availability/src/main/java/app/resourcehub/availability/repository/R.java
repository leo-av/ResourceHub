package app.resourcehub.availability.repository;

import app.resourcehub.availability.repository.logic.local.CalendarExceptionRepository;
import app.resourcehub.availability.repository.logic.local.TemporalBlockRepository;
import app.resourcehub.availability.repository.logic.local.WeeklyScheduleRepository;
import javax.sql.DataSource;

public final class R {

    private static WeeklyScheduleRepository weeklyScheduleRepository;
    private static TemporalBlockRepository temporalBlockRepository;
    private static CalendarExceptionRepository calendarExceptionRepository;

    private R() {
        throw new UnsupportedOperationException("Static XF repository injector");
    }

    public static void init() {
        weeklyScheduleRepository = new WeeklyScheduleRepository();
        temporalBlockRepository = new TemporalBlockRepository();
        calendarExceptionRepository = new CalendarExceptionRepository();
    }

    public static void init(DataSource dataSource) {
        weeklyScheduleRepository = new WeeklyScheduleRepository(dataSource);
        temporalBlockRepository = new TemporalBlockRepository(dataSource);
        calendarExceptionRepository = new CalendarExceptionRepository(dataSource);
    }

    public static WeeklyScheduleRepository weeklySchedules() {
        if (weeklyScheduleRepository == null) {
            throw new IllegalStateException("R.init() has not been called");
        }
        return weeklyScheduleRepository;
    }

    public static TemporalBlockRepository temporalBlocks() {
        if (temporalBlockRepository == null) {
            throw new IllegalStateException("R.init() has not been called");
        }
        return temporalBlockRepository;
    }

    public static CalendarExceptionRepository exceptions() {
        if (calendarExceptionRepository == null) {
            throw new IllegalStateException("R.init() has not been called");
        }
        return calendarExceptionRepository;
    }

    public static void terminate() {
        weeklyScheduleRepository = null;
        temporalBlockRepository = null;
        calendarExceptionRepository = null;
    }
}
