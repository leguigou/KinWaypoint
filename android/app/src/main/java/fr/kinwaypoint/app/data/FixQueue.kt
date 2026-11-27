package fr.kinwaypoint.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import fr.kinwaypoint.app.net.LocationFix

/**
 * File d'attente persistante des positions non encore acceptées par le serveur.
 *
 * Une position n'est retirée qu'après un `200` du serveur : sans réseau (tunnel,
 * zone blanche, mode avion), elle attend et part au retour de la connexion.
 * Les alertes (SOS) passent toujours avant l'historique en retard.
 */
class FixQueue private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "fix_queue.db", null, 4) {

    companion object {
        @Volatile private var instance: FixQueue? = null

        /** Une seule instance par processus : la base reste ouverte, jamais fermée en plein envoi. */
        fun get(context: Context): FixQueue =
            instance ?: synchronized(this) { instance ?: FixQueue(context).also { instance = it } }

        /** ~3,5 jours à une position par minute : au-delà, on jette les plus anciennes. */
        private const val MAX_ROWS = 5000
        /** Aligné sur la rétention serveur : inutile d'envoyer ce qui serait aussitôt purgé. */
        private const val MAX_AGE_MS = 30L * 24 * 3600 * 1000
    }

    data class Entry(val id: Long, val fix: LocationFix)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE fixes (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                time INTEGER NOT NULL,
                lat REAL NOT NULL,
                lon REAL NOT NULL,
                accuracy REAL NOT NULL,
                altitude REAL NOT NULL,
                speed REAL NOT NULL,
                bearing REAL NOT NULL,
                battery INTEGER NOT NULL,
                charging INTEGER NOT NULL,
                alarm TEXT,
                fix_age INTEGER,
                ringer TEXT,
                wifi TEXT,
                steps INTEGER,
                activity TEXT,
                kmh REAL,
                wifi_on INTEGER
            )
            """.trimIndent(),
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v2 : état du téléphone (sonnerie, Wi-Fi) envoyé avec la position.
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE fixes ADD COLUMN ringer TEXT")
            db.execSQL("ALTER TABLE fixes ADD COLUMN wifi TEXT")
        }
        // v3 : podomètre et activité estimée.
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE fixes ADD COLUMN steps INTEGER")
            db.execSQL("ALTER TABLE fixes ADD COLUMN activity TEXT")
            db.execSQL("ALTER TABLE fixes ADD COLUMN kmh REAL")
        }
        // v4 : Wi-Fi activé ou coupé (même sans réseau connecté).
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE fixes ADD COLUMN wifi_on INTEGER")
        }
    }

    @Synchronized
    fun add(fix: LocationFix): Long {
        val values = ContentValues().apply {
            put("time", fix.time)
            put("lat", fix.latitude)
            put("lon", fix.longitude)
            put("accuracy", fix.accuracy)
            put("altitude", fix.altitude)
            put("speed", fix.speed)
            put("bearing", fix.bearing)
            put("battery", fix.battery)
            put("charging", if (fix.charging) 1 else 0)
            put("alarm", fix.alarm)
            put("fix_age", fix.fixAgeSeconds)
            put("ringer", fix.ringer)
            put("wifi", fix.wifi)
            put("steps", fix.steps)
            put("activity", fix.activity)
            put("kmh", fix.kmh)
            put("wifi_on", fix.wifiOn?.let { if (it) 1 else 0 })
        }
        val db = writableDatabase
        val id = db.insert("fixes", null, values)
        trim(db)
        return id
    }

    /** Alertes d'abord, puis le reste du plus ancien au plus récent. */
    @Synchronized
    fun peek(limit: Int): List<Entry> {
        val result = mutableListOf<Entry>()
        readableDatabase.rawQuery(
            "SELECT id, time, lat, lon, accuracy, altitude, speed, bearing, battery, charging, alarm, fix_age, ringer, wifi, steps, activity, kmh, wifi_on " +
                "FROM fixes ORDER BY (alarm IS NULL), time, id LIMIT ?",
            arrayOf(limit.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                result += Entry(
                    id = c.getLong(0),
                    fix = LocationFix(
                        time = c.getLong(1),
                        latitude = c.getDouble(2),
                        longitude = c.getDouble(3),
                        accuracy = c.getFloat(4),
                        altitude = c.getDouble(5),
                        speed = c.getFloat(6),
                        bearing = c.getFloat(7),
                        battery = c.getInt(8),
                        charging = c.getInt(9) == 1,
                        alarm = if (c.isNull(10)) null else c.getString(10),
                        fixAgeSeconds = if (c.isNull(11)) null else c.getLong(11),
                        ringer = if (c.isNull(12)) null else c.getString(12),
                        wifi = if (c.isNull(13)) null else c.getString(13),
                        steps = if (c.isNull(14)) null else c.getInt(14),
                        activity = if (c.isNull(15)) null else c.getString(15),
                        kmh = if (c.isNull(16)) null else c.getDouble(16),
                        wifiOn = if (c.isNull(17)) null else c.getInt(17) == 1,
                    ),
                )
            }
        }
        return result
    }

    @Synchronized
    fun remove(id: Long) {
        writableDatabase.delete("fixes", "id = ?", arrayOf(id.toString()))
    }

    @Synchronized
    fun size(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM fixes", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }

    @Synchronized
    fun hasPendingAlarm(): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM fixes WHERE alarm = 'SOS' LIMIT 1", null).use { it.moveToFirst() }

    private fun trim(db: SQLiteDatabase) {
        val cutoff = System.currentTimeMillis() - MAX_AGE_MS
        db.delete("fixes", "alarm IS NULL AND time < ?", arrayOf(cutoff.toString()))
        db.execSQL(
            "DELETE FROM fixes WHERE alarm IS NULL AND id NOT IN " +
                "(SELECT id FROM fixes ORDER BY time DESC, id DESC LIMIT $MAX_ROWS)",
        )
    }
}
