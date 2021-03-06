package org.osmdroid.tileprovider.modules;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.drawable.Drawable;
import android.util.Log;

import org.osmdroid.api.IMapView;
import org.osmdroid.config.Configuration;
import org.osmdroid.tileprovider.tilesource.ITileSource;
import org.osmdroid.tileprovider.util.StreamUtils;
import org.osmdroid.util.MapTileIndex;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * An implementation of {@link IFilesystemCache} based on the original TileWriter. It writes tiles to a sqlite database.
 * It does NOT support expiration and provides more of a MOBAC like functionality (non-expiring file archives).
 * Uses the same schema as MOBAC osm sqlite and the {@link DatabaseFileArchive}
 * <p>
 * https://github.com/osmdroid/osmdroid/issues/348
 * @see SqlTileWriter
 * @see DatabaseFileArchive
 * @author Alex O'Ree
 * @since 5.2 7/8/16.
 */
public class SqliteArchiveTileWriter implements IFilesystemCache {
    final File db_file;
    final SQLiteDatabase db;
    final int questimate = 8000;
    static boolean hasInited = false;

    public SqliteArchiveTileWriter(String outputFile) throws Exception {
        // do this in the background because it takes a long time
        db_file = new File(outputFile);
        try {
            db = SQLiteDatabase.openOrCreateDatabase(db_file.getAbsolutePath(), null);
        } catch (Exception ex) {
            throw new Exception("Trouble creating database file at " + outputFile, ex);
        }
        try {

            db.execSQL("CREATE TABLE IF NOT EXISTS " + DatabaseFileArchive.TABLE + " (" + DatabaseFileArchive.COLUMN_KEY + " INTEGER , " + DatabaseFileArchive.COLUMN_PROVIDER + " TEXT, tile BLOB, PRIMARY KEY (key, provider));");
        } catch (Throwable t) {
            t.printStackTrace();
            Log.d(IMapView.LOGTAG, "error setting db schema, it probably exists already", t);
            // throw new IOException("Trouble creating database file"+ t.getMessage());
        }
    }

    @Override
    public boolean saveFile(final ITileSource pTileSourceInfo, final long pMapTileIndex, final InputStream pStream, final Long pExpirationTime) {
        ByteArrayOutputStream bos = null;
        try {
            ContentValues cv = new ContentValues();
            final long index = SqlTileWriter.getIndex(pMapTileIndex);
            cv.put(DatabaseFileArchive.COLUMN_PROVIDER, pTileSourceInfo.name());

            byte[] buffer = new byte[512];
            int l;
            bos = new ByteArrayOutputStream();
            while( (l = pStream.read(buffer)) != -1 )
                bos.write(buffer, 0, l);
            byte[] bits = bos.toByteArray(); // if a variable is required at all

            cv.put(DatabaseFileArchive.COLUMN_KEY, index);
            cv.put(DatabaseFileArchive.COLUMN_TILE, bits);
            db.insert(DatabaseFileArchive.TABLE, null, cv);
            if (Configuration.getInstance().isDebugMode())
                Log.d(IMapView.LOGTAG, "tile inserted " + pTileSourceInfo.name() + MapTileIndex.toString(pMapTileIndex));
        } catch (Throwable ex) {
            Log.e(IMapView.LOGTAG, "Unable to store cached tile from " + pTileSourceInfo.name() + " " + MapTileIndex.toString(pMapTileIndex), ex);
        }  finally {
            try {
                bos.close();
            } catch (IOException e) {

            }
        }
        return false;
    }


    @Override
    public boolean exists(ITileSource pTileSource, final long pMapTileIndex) {
        try {
            final long index = SqlTileWriter.getIndex(pMapTileIndex);
            final Cursor cur = getTileCursor(SqlTileWriter.getPrimaryKeyParameters(index, pTileSource));

            final boolean result = (cur.getCount() != 0);
            cur.close();
            return result;
        } catch (Throwable ex) {
            Log.e(IMapView.LOGTAG, "Unable to store cached tile from " + pTileSource.name() + " " + MapTileIndex.toString(pMapTileIndex), ex);
        }
        return false;
    }

    @Override
    public void onDetach() {
        if (db != null)
            db.close();
    }

    @Override
    public boolean remove(final ITileSource tileSource, final long pMapTileIndex) {
        //not supported
        return false;
    }

    @Override
    public Long getExpirationTimestamp(final ITileSource pTileSource, final long pMapTileIndex) {
        return null;
    }

    /**
     * For optimization reasons
     * @since 5.6.5
     */
    private static final String[] queryColumns = {DatabaseFileArchive.COLUMN_TILE};

    /**
     *
     * @since 5.6.5
     * @param pPrimaryKeyParameters
     * @return
     */
    public Cursor getTileCursor(final String[] pPrimaryKeyParameters) {
        return db.query(DatabaseFileArchive.TABLE, queryColumns, SqlTileWriter.getPrimaryKey(), pPrimaryKeyParameters, null, null, null);
    }

    /**
     *
     * @since 5.6.5
     */
    @Override
    public Drawable loadTile(final ITileSource pTileSource, final long pMapTileIndex) throws Exception{
        InputStream inputStream = null;
        try {
            final long index = SqlTileWriter.getIndex(pMapTileIndex);
            final Cursor cur = getTileCursor(SqlTileWriter.getPrimaryKeyParameters(index, pTileSource));
            byte[] bits=null;

            if(cur.moveToFirst()) {
                bits = cur.getBlob(cur.getColumnIndex(DatabaseFileArchive.COLUMN_TILE));
            }
            cur.close();
            if (bits==null) {
                if (Configuration.getInstance().isDebugMode()) {
                    Log.d(IMapView.LOGTAG,"SqlCache - Tile doesn't exist: " +pTileSource.name() + MapTileIndex.toString(pMapTileIndex));
                }
                return null;
            }
            inputStream = new ByteArrayInputStream(bits);
            return pTileSource.getDrawable(inputStream);
        } finally {
            if (inputStream != null) {
                StreamUtils.closeStream(inputStream);
            }
        }
    }
}
