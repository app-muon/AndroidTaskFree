package com.taskfree.app.data.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 1. Add new columns with defaults
        db.execSQL("ALTER TABLE Task ADD COLUMN status TEXT NOT NULL DEFAULT 'TODO'")
        db.execSQL("ALTER TABLE Task ADD COLUMN isArchived INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE Task ADD COLUMN reminderTime TEXT")
        db.execSQL("ALTER TABLE Task RENAME COLUMN todoOrder TO todoPageOrder")
        // 2. Set status = 'DONE' where completedDate is not null
        db.execSQL(
            """
            UPDATE Task 
            SET status = 'DONE' 
            WHERE completedDate IS NOT NULL
        """.trimIndent()
        )
    }
}
val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 1. Add new column baseDate
        db.execSQL("ALTER TABLE Task ADD COLUMN baseDate TEXT")

        // 2. Set baseDate = due where recurrence != 'NONE'
        db.execSQL(
            """
            UPDATE Task
            SET baseDate = due
            WHERE recurrence != 'NONE'
        """.trimIndent()
        )

        // 3. Update recurrence from 'EVERY_DAY' → 'DAILY'
        db.execSQL(
            """
            UPDATE Task
            SET recurrence = 'DAILY'
            WHERE recurrence = 'EVERY_DAY'
        """.trimIndent()
        )

        // 4. Update all recurrence starting with 'EVERY_' (but not 'EVERY_DAY') → 'WEEKLY'
        db.execSQL(
            """
            UPDATE Task
            SET recurrence = 'WEEKLY'
            WHERE recurrence LIKE 'EVERY_%'
              AND recurrence != 'EVERY_DAY'
        """.trimIndent()
        )
    }
}


val MIGRATION_FIX_RECURRENCE = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            UPDATE Task
            SET recurrence = 'WEEKLY'
            WHERE recurrence LIKE 'WEEKLY_%'
        """.trimIndent()
        )
    }
}

val MIGRATION_13_14 = object : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 1  create new table with INTEGER dates
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS Task_new (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                categoryId INTEGER NOT NULL,
                text TEXT NOT NULL,
                due INTEGER,
                baseDate INTEGER,
                taskPageOrder INTEGER NOT NULL,
                todoPageOrder INTEGER NOT NULL,
                completedDate INTEGER,
                recurrence TEXT NOT NULL DEFAULT 'NONE',
                status TEXT NOT NULL DEFAULT 'TODO',
                isArchived INTEGER NOT NULL DEFAULT 0,
                reminderTime INTEGER,
                FOREIGN KEY(categoryId) REFERENCES Category(id) ON DELETE CASCADE
            )
        """.trimIndent())

        // 2  copy, converting yyyy-MM-dd → epoch-day
        db.execSQL("""
            INSERT INTO Task_new
              (id, categoryId, text,
               due, baseDate, taskPageOrder, todoPageOrder,
               completedDate, recurrence, status, isArchived, reminderTime)
            SELECT id, categoryId, text,
                   CAST((julianday(due)          - 2440587.5) AS INTEGER),
                   CAST((julianday(baseDate)     - 2440587.5) AS INTEGER),
                   taskPageOrder, todoPageOrder,
                   CAST((julianday(completedDate) - 2440587.5) AS INTEGER),
                   recurrence, status, isArchived, 
                   CAST(strftime('%s', reminderTime) * 1000 AS INTEGER) AS reminderTime
            FROM Task
        """.trimIndent())

        // 3  swap tables
        db.execSQL("DROP TABLE Task")
        db.execSQL("ALTER TABLE Task_new RENAME TO Task")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_Task_categoryId ON Task(categoryId)")
    }
}
val MIGRATION_14_15 = object : Migration(14, 15) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Fix bad 'null' strings left in the database from earlier versions
        db.execSQL("UPDATE Task SET due = NULL WHERE due = 'null'")
        db.execSQL("UPDATE Task SET baseDate = NULL WHERE baseDate = 'null'")
        db.execSQL("UPDATE Task SET completedDate = NULL WHERE completedDate = 'null'")
        db.execSQL("UPDATE Task SET reminderTime = NULL WHERE reminderTime = 'null'")
    }
}

val MIGRATION_15_16 = object : Migration(15, 16) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.beginTransaction()
        try {
            // 1) Rename the existing Task table
            db.execSQL("ALTER TABLE `Task` RENAME TO `Task_old`;")

            // 2) Drop the old index so it doesn’t conflict
            db.execSQL("DROP INDEX IF EXISTS `index_Task_categoryId`;")

            // 3) Create the new Task schema with renamed columns
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `Task` (
                  `id`                     INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                  `categoryId`             INTEGER NOT NULL,
                  `text`                   TEXT    NOT NULL,
                  `due`                    INTEGER,
                  `baseDate`               INTEGER,
                  `singleCategoryPageOrder`INTEGER NOT NULL,
                  `allCategoryPageOrder`   INTEGER NOT NULL,
                  `completedDate`          INTEGER,
                  `recurrence`             TEXT    NOT NULL DEFAULT 'NONE',
                  `status`                 TEXT    NOT NULL DEFAULT 'TODO',
                  `isArchived`             INTEGER NOT NULL DEFAULT 0,
                  `reminderTime`           INTEGER,
                  FOREIGN KEY(`categoryId`)
                    REFERENCES `Category`(`id`)
                    ON DELETE CASCADE
                );
            """.trimIndent())

            // 4) Recreate the index on the new Task table
            db.execSQL("""
                CREATE INDEX IF NOT EXISTS `index_Task_categoryId`
                  ON `Task` (`categoryId`);
            """.trimIndent())

            // 5) Copy data from Task_old → Task,
            //    mapping old “taskPageOrder” → “singleCategoryPageOrder”
            //           old “todoPageOrder” → “allCategoryPageOrder”
            db.execSQL("""
                INSERT INTO `Task` (
                  id,
                  categoryId,
                  text,
                  due,
                  baseDate,
                  singleCategoryPageOrder,
                  allCategoryPageOrder,
                  completedDate,
                  recurrence,
                  status,
                  isArchived,
                  reminderTime
                )
                SELECT
                  id,
                  categoryId,
                  text,
                  due,
                  baseDate,
                  taskPageOrder,    -- old name
                  todoPageOrder,    -- old name
                  completedDate,
                  IFNULL(recurrence, 'NONE'),
                  IFNULL(status, 'TODO'),
                  isArchived,
                  reminderTime
                FROM `Task_old`;
            """.trimIndent())

            // 6) Drop the old table now that data has been copied
            db.execSQL("DROP TABLE `Task_old`;")

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}


