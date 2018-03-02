package com.aki.beetag;

import android.app.Activity;
import android.app.Dialog;
import android.app.DialogFragment;
import android.app.TimePickerDialog;
import android.arch.persistence.room.Room;
import android.arch.persistence.room.RoomDatabase;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.PointF;
import android.graphics.Rect;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.support.design.widget.FloatingActionButton;
import android.text.format.DateFormat;
import android.view.DragEvent;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.RelativeLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.TimePicker;
import android.widget.Toast;

import com.davemorrissey.labs.subscaleview.ImageSource;
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView;

import org.joda.time.DateTime;
import org.joda.time.MutableDateTime;
import org.json.JSONArray;
import org.json.JSONException;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessageUnpacker;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import ar.com.hjg.pngj.ImageInfo;
import ar.com.hjg.pngj.PngWriter;

public class DecodingActivity
        extends Activity
        implements TagTimePickerFragment.OnTagTimePickedListener,
                   TagDatePickerFragment.OnTagDatePickedListener {

    public enum ViewMode {
        TAGGING_MODE, EDITING_MODE
    }
    private ViewMode viewMode;

    private TagView tagView;
    private RelativeLayout tagInfoLayout;
    private ScrollView tagInfoScrollView;
    private FloatingActionButton tagButton;
    private ImageButton cancelTagEditingButton;
    private ImageButton deleteTagButton;
    private ImageButton saveEditedTagButton;
    private TextView textInputDoneButton;

    private TextView beeIdTextView;
    private EditText tagLabelEditText;
    private EditText tagNotesEditText;
    private TextView tagDateTextView;
    private TextView tagTimeTextView;
    private TextView detectionIdTextView;

    private File imageFolder;
    private Uri imageUri;

    private TagDatabase database = null;
    private TagDao dao;

    private Tag currentlyEditedTag;
    private double dragStartingAngle;
    private double tagOrientationBeforeDrag;

    private class ServerRequestTask extends AsyncTask<DecodingData, Void, DecodingResult> {
        @Override
        protected DecodingResult doInBackground(DecodingData... decodingData) {
            DecodingData data = decodingData[0];
            BitmapFactory.Options options = new BitmapFactory.Options();
            Bitmap imageBitmap = BitmapFactory.decodeFile(new File(data.imageUri.getPath()).getAbsolutePath(), options);
            int imageWidth = options.outWidth;
            int imageHeight = options.outHeight;
            PointF tagCenter = new PointF();
            switch (data.appliedOrientation) {
                case 90:
                    // rotate tagCenter by 90 degrees, counter-clockwise
                    tagCenter.set(data.tagCenter.y, imageHeight-data.tagCenter.x);
                    break;
                case 180:
                    // rotate tagCenter by 180 degrees
                    tagCenter.set(imageWidth-data.tagCenter.x, imageHeight-data.tagCenter.y);
                    break;
                case 270:
                    // rotate tagCenter by 90 degrees, clockwise
                    tagCenter.set(imageWidth-data.tagCenter.y, data.tagCenter.x);
                    break;
                default:
                    tagCenter.set(data.tagCenter);
                    break;
            }

            Matrix rotationMatrix = new Matrix();
            rotationMatrix.postRotate(data.appliedOrientation);
            int tagSizeTargetInPx = 50;
            float tagScaleToTarget = tagSizeTargetInPx / data.tagSizeInPx;
            rotationMatrix.postScale(tagScaleToTarget, tagScaleToTarget);

            // increase size of crop square by 30% on each side for padding
            ImageSquare cropSquare = new ImageSquare(tagCenter, data.tagSizeInPx * 2f);
            Rect imageCropZone = cropSquare.getImageOverlap(imageWidth, imageHeight);
            Bitmap croppedTag = Bitmap.createBitmap(
                    imageBitmap,
                    imageCropZone.left,
                    imageCropZone.top,
                    imageCropZone.right - imageCropZone.left,
                    imageCropZone.bottom - imageCropZone.top,
                    rotationMatrix,
                    // TODO: check results with filter = true
                    false);

            // Intermediate stream that the PNG is written to,
            // to find out the overall data size.
            // After writing, the connection is opened with the
            // appropriate data length.
            // (ChunkedStreamingMode is not supported by the bb_pipeline_api,
            // so we have to use FixedLengthStreamingMode)
            ByteArrayOutputStream pngStream = new ByteArrayOutputStream();
            int cutoutWidth = croppedTag.getWidth();
            int cutoutHeight = croppedTag.getHeight();

            // single channel (grayscale, no alpha) PNG, 8 bit, not indexed
            ImageInfo imgInfo = new ImageInfo(cutoutWidth, cutoutHeight, 8, false, true, false);
            PngWriter pngWriter = new PngWriter(pngStream, imgInfo);
            int[] grayLine = new int[cutoutWidth];
            for (int y = 0; y < cutoutHeight; y++) {
                // write PNG line by line
                for (int x = 0; x < cutoutWidth; x++) {
                    int pixel = croppedTag.getPixel(x, y);
                    int grayValue = (int) (0.2125 * ((pixel >> 16) & 0xff));
                    grayValue += (int) (0.7154 * ((pixel >> 8) & 0xff));
                    grayValue += (int) (0.0721 * (pixel & 0xff));
                    grayLine[x] = grayValue;
                }
                pngWriter.writeRowInt(grayLine);
            }
            pngWriter.end();

            BufferedOutputStream out = null;
            BufferedInputStream in = null;
            HttpURLConnection connection = null;
            List<Tag> tags = new ArrayList<>();
            int resultCode;

            try {
                connection = (HttpURLConnection) data.serverUrl.openConnection();
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Content-Type", "application/octet-stream");
                // ChunkedStreamingMode is not supported by the bb_pipeline_api, so use fixed length
                connection.setFixedLengthStreamingMode(pngStream.size());
                connection.setDoOutput(true);
                connection.setDoInput(true);

                out = new BufferedOutputStream(connection.getOutputStream());
                pngStream.writeTo(out);
                out.flush();
                in = new BufferedInputStream(connection.getInputStream());

                MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(in);
                int mapSize = unpacker.unpackMapHeader();
                ArrayList<ArrayList<Integer>> ids = new ArrayList<>();
                ArrayList<Double> orientations = new ArrayList<>();
                for (int i = 0; i < mapSize; i++) {
                    String key = unpacker.unpackString();
                    switch (key) {
                        case "IDs":
                            int idsListLength = unpacker.unpackArrayHeader();
                            ArrayList<Integer> id;
                            for (int j = 0; j < idsListLength; j++) {
                                id = new ArrayList<>();
                                // will always be 12 (12 bits)
                                int idLength = unpacker.unpackArrayHeader();
                                for (int k = 0; k < idLength; k++) {
                                    id.add((int) Math.round(unpacker.unpackDouble()));
                                }
                                ids.add(id);
                            }
                            break;
                        case "Orientations":
                            int orientationsListLength = unpacker.unpackArrayHeader();
                            for (int j = 0; j < orientationsListLength; j++) {
                                // will always be 3 (3 angle dimensions: z, y, x)
                                int orientationLength = unpacker.unpackArrayHeader();
                                for (int k = 0; k < orientationLength; k++) {
                                    // only use the z angle
                                    if (k == 0) {
                                        orientations.add(unpacker.unpackDouble());
                                    } else {
                                        unpacker.unpackDouble();
                                    }
                                }
                            }
                            break;
                    }
                }
                int tagCount = Math.min(ids.size(), orientations.size());
                for (int i = 0; i < tagCount; i++) {
                    Tag tag = new Tag();
                    tag.setCenterX(data.tagCenter.x);
                    tag.setCenterY(data.tagCenter.y);
                    tag.setRadius(data.tagSizeInPx / 2);
                    tag.setImageName(imageUri.getLastPathSegment());
                    tag.setOrientation(orientations.get(i));
                    tag.setBeeId(Tag.bitIdToDecimalId(ids.get(i)));
                    tag.setDate(new DateTime(new File(imageUri.getPath()).lastModified()));
                    tags.add(tag);
                }
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                try {
                    if (out != null) {
                        out.close();
                    }
                    if (in != null) {
                        in.close();
                    }
                    if (connection != null) {
                        connection.disconnect();
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            if (tags.isEmpty()) {
                resultCode = DecodingResult.TAG_NOT_FOUND;
            } else {
                resultCode = DecodingResult.OK;
            }
            return new DecodingResult(data, tags, resultCode);
        }

        @Override
        protected void onPostExecute(DecodingResult result) {
            Tag resultTag;
            if (result.resultCode == DecodingResult.TAG_NOT_FOUND) {
                Toast.makeText(getApplicationContext(), "No tag found :(", Toast.LENGTH_LONG).show();
                resultTag = new Tag();
                resultTag.setBeeId(0);
                resultTag.setImageName(imageUri.getLastPathSegment());
                resultTag.setCenterX(result.input.tagCenter.x);
                resultTag.setCenterY(result.input.tagCenter.y);
                resultTag.setRadius(result.input.tagSizeInPx / 2);
                resultTag.setOrientation(0);
                resultTag.setDate(new DateTime(new File(imageUri.getPath()).lastModified()));
            } else {
                resultTag = result.decodedTags.get(0);
            }
            new DatabaseInsertTask().execute(resultTag);
        }
    }

    private class GetTagsTask extends AsyncTask<Uri, Void, List<Tag>> {
        @Override
        protected List<Tag> doInBackground(Uri... uris) {
            return dao.loadTagsByImage(uris[0].getLastPathSegment());
        }

        @Override
        protected void onPostExecute(List<Tag> tags) {
            tagView.setTagsOnImage(tags);
        }
    }

    private class DatabaseInsertTask extends AsyncTask<Tag, Void, Void> {
        @Override
        protected Void doInBackground(Tag... tags) {
            dao.insertTags(tags[0]);
            return null;
        }

        @Override
        protected void onPostExecute(Void aVoid) {
            new GetTagsTask().execute(imageUri);
        }
    }

    private class DatabaseDeleteTask extends AsyncTask<Tag, Void, Void> {
        @Override
        protected Void doInBackground(Tag... tags) {
            dao.deleteTags(tags[0]);
            return null;
        }

        @Override
        protected void onPostExecute(Void aVoid) {
            new GetTagsTask().execute(imageUri);
        }
    }

    private class DatabaseUpdateTask extends AsyncTask<Tag, Void, Void> {
        @Override
        protected Void doInBackground(Tag... tags) {
            dao.updateTags(tags[0]);
            return null;
        }

        @Override
        protected void onPostExecute(Void aVoid) {
            Toast.makeText(getApplicationContext(), "Tag saved.", Toast.LENGTH_SHORT).show();
            new GetTagsTask().execute(imageUri);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_decoding);

        Intent intent = getIntent();
        imageUri = intent.getData();
        imageFolder = new File(((Uri) intent.getExtras().get("imageFolder")).getPath());

        RoomDatabase.Builder<TagDatabase> databaseBuilder = Room.databaseBuilder(getApplicationContext(), TagDatabase.class, "beetag-database");
        databaseBuilder.fallbackToDestructiveMigration();
        database = databaseBuilder.build();
        dao = database.getDao();

        tagInfoLayout = findViewById(R.id.relativelayout_tag_info);
        tagInfoScrollView = findViewById(R.id.scrollview_tag_info);

        View.OnFocusChangeListener onFocusChangeListener = new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) {
                    cancelTagEditingButton.setVisibility(View.INVISIBLE);
                    deleteTagButton.setVisibility(View.INVISIBLE);
                    saveEditedTagButton.setVisibility(View.INVISIBLE);
                    textInputDoneButton.setVisibility(View.VISIBLE);
                } else {
                    cancelTagEditingButton.setVisibility(View.VISIBLE);
                    deleteTagButton.setVisibility(View.VISIBLE);
                    saveEditedTagButton.setVisibility(View.VISIBLE);
                    textInputDoneButton.setVisibility(View.INVISIBLE);
                }
            }
        };
        tagLabelEditText = findViewById(R.id.edittext_tag_info_label);
        tagLabelEditText.setOnFocusChangeListener(onFocusChangeListener);
        tagNotesEditText = findViewById(R.id.edittext_tag_info_notes);
        tagNotesEditText.setOnFocusChangeListener(onFocusChangeListener);

        beeIdTextView = findViewById(R.id.textview_tag_info_bee_id);

        tagDateTextView = findViewById(R.id.textview_tag_info_date);
        tagDateTextView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (!tagView.isReady() || viewMode != ViewMode.EDITING_MODE) {
                    return;
                }

                DialogFragment tagDatePickerFragment = new TagDatePickerFragment();
                tagDatePickerFragment.show(getFragmentManager(), "tagDatePicker");
            }
        });

        tagTimeTextView = findViewById(R.id.textview_tag_info_time);
        tagTimeTextView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (!tagView.isReady() || viewMode != ViewMode.EDITING_MODE) {
                    return;
                }

                DialogFragment tagTimePickerFragment = new TagTimePickerFragment();
                tagTimePickerFragment.show(getFragmentManager(), "tagTimePicker");
            }
        });

        detectionIdTextView = findViewById(R.id.textview_tag_info_detection_id);

        tagButton = findViewById(R.id.button_tag);
        tagButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (!tagView.isReady() || viewMode != ViewMode.TAGGING_MODE) {
                    return;
                }

                URL serverUrl = null;
                try {
                    serverUrl = buildUrl();
                } catch (JSONException e) {
                    e.printStackTrace();
                } catch (MalformedURLException e) {
                    e.printStackTrace();
                }
                PointF tagCenter = tagView.getCenter();
                float tagSizeInPx = (tagView.getTagCircleRadius() * 2) / tagView.getScale();
                int appliedOrientation = tagView.getAppliedOrientation();

                DecodingData tagData = new DecodingData(
                        serverUrl,
                        imageUri,
                        tagCenter,
                        tagSizeInPx,
                        appliedOrientation);
                new ServerRequestTask().execute(tagData);
            }
        });

        cancelTagEditingButton = findViewById(R.id.button_tag_info_cancel);
        cancelTagEditingButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!tagView.isReady() || viewMode != ViewMode.EDITING_MODE) {
                    return;
                }

                new GetTagsTask().execute(imageUri);
                setViewMode(ViewMode.TAGGING_MODE);
            }
        });

        deleteTagButton = findViewById(R.id.button_delete_tag);
        deleteTagButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (!tagView.isReady() || viewMode != ViewMode.EDITING_MODE) {
                    return;
                }

                if (currentlyEditedTag != null) {
                    new DatabaseDeleteTask().execute(currentlyEditedTag);
                    setViewMode(ViewMode.TAGGING_MODE);
                }
            }
        });

        textInputDoneButton = findViewById(R.id.button_text_input_done);
        textInputDoneButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!tagView.isReady() || viewMode != ViewMode.EDITING_MODE) {
                    return;
                }

                currentlyEditedTag.setLabel(tagLabelEditText.getText().toString());
                currentlyEditedTag.setNotes(tagNotesEditText.getText().toString());

                View focusedView = getCurrentFocus();
                if (focusedView != null) {
                    InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (imm != null) {
                        imm.hideSoftInputFromWindow(focusedView.getWindowToken(), 0);
                    }
                    tagInfoLayout.requestFocus();
                }
                tagInfoScrollView.smoothScrollTo(0, 0);
            }
        });

        saveEditedTagButton = findViewById(R.id.button_save_edited_tag);
        saveEditedTagButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (!tagView.isReady() || viewMode != ViewMode.EDITING_MODE) {
                    return;
                }

                if (currentlyEditedTag != null) {
                    new DatabaseUpdateTask().execute(currentlyEditedTag);
                    setViewMode(ViewMode.TAGGING_MODE);
                }
            }
        });

        tagView = findViewById(R.id.tag_view);
        tagView.setOrientation(SubsamplingScaleImageView.ORIENTATION_USE_EXIF);
        tagView.setMinimumDpi(10);
        tagView.setDoubleTapZoomStyle(SubsamplingScaleImageView.ZOOM_FOCUS_CENTER);

        final GestureDetector gestureDetector = new GestureDetector(getApplicationContext(), new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                if (!tagView.isReady()) {
                    return false;
                }

                PointF tap = tagView.viewToSourceCoord(e.getX(), e.getY());
                if (viewMode == ViewMode.TAGGING_MODE) {
                    Tag tappedTag = tagView.tagAtPosition(tap);
                    if (tappedTag != null) {
                        currentlyEditedTag = tappedTag;
                        setViewMode(ViewMode.EDITING_MODE);
                    } else {
                        return false;
                    }
                } else if (viewMode == ViewMode.EDITING_MODE) {
                    int toggledBitPosition = tagView.bitSegmentAtPosition(tap, currentlyEditedTag);
                    ArrayList<Integer> id = Tag.decimalIdToBitId(currentlyEditedTag.getBeeId());
                    if (toggledBitPosition != -1) {
                        // invert bit that was tapped
                        id.set(toggledBitPosition, 1 - id.get(toggledBitPosition));
                        currentlyEditedTag.setBeeId(Tag.bitIdToDecimalId(id));
                        // update view to show changed tag
                        tagView.invalidate();
                        beeIdTextView.setText(Integer.toString(currentlyEditedTag.getBeeId()));
                    } else {
                        return false;
                    }
                }
                return true;
            }

            @Override
            public void onLongPress(MotionEvent e) {
                if (!tagView.isReady()) {
                    return;
                }

                if (viewMode == ViewMode.EDITING_MODE) {
                    if (Build.VERSION.SDK_INT < 24) {
                        tagView.startDrag(
                                null,
                                new View.DragShadowBuilder(),
                                null,
                                0);
                    } else {
                        tagView.startDragAndDrop(
                                null,
                                new View.DragShadowBuilder(),
                                null,
                                0);
                    }
                }
            }
        });
        tagView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View view, MotionEvent motionEvent) {
                return gestureDetector.onTouchEvent(motionEvent);
            }
        });
        tagView.setOnDragListener(new View.OnDragListener() {
            @Override
            public boolean onDrag(View view, DragEvent dragEvent) {
                final int action = dragEvent.getAction();
                PointF locationInSource = tagView.viewToSourceCoord(dragEvent.getX(), dragEvent.getY());
                PointF tagCenterToLocation = new PointF(
                        locationInSource.x - currentlyEditedTag.getCenterX(),
                        locationInSource.y - currentlyEditedTag.getCenterY()
                );
                double locationAngle = Math.toDegrees(Math.atan2(tagCenterToLocation.y, tagCenterToLocation.x));
                double tagDragAngle;
                double newTagAngle;
                switch (action) {
                    case DragEvent.ACTION_DRAG_STARTED:
                        dragStartingAngle = locationAngle;
                        tagOrientationBeforeDrag = currentlyEditedTag.getOrientation();
                        break;
                    case DragEvent.ACTION_DRAG_LOCATION:
                        tagDragAngle = ((locationAngle - dragStartingAngle) + 360) % 360;
                        newTagAngle = (Math.toDegrees(tagOrientationBeforeDrag) + tagDragAngle) % 360;
                        currentlyEditedTag.setOrientation(Math.toRadians(newTagAngle));
                        break;
                    case DragEvent.ACTION_DROP:
                        tagDragAngle = ((locationAngle - dragStartingAngle) + 360) % 360;
                        newTagAngle = (Math.toDegrees(tagOrientationBeforeDrag) + tagDragAngle) % 360;
                        currentlyEditedTag.setOrientation(Math.toRadians(newTagAngle));
                        break;
                }
                tagView.invalidate();
                return true;
            }
        });

        new GetTagsTask().execute(imageUri);
        tagView.setImage(ImageSource.uri(imageUri));
        setViewMode(ViewMode.TAGGING_MODE);
    }

    @Override
    public void onTagTimePicked(int hour, int minute) {
        if (!tagView.isReady() || viewMode != ViewMode.EDITING_MODE) {
            return;
        }

        MutableDateTime mutableTagDate = currentlyEditedTag.getDate().toMutableDateTime();
        mutableTagDate.setHourOfDay(hour);
        mutableTagDate.setMinuteOfHour(minute);
        DateTime tagDate = mutableTagDate.toDateTime();
        currentlyEditedTag.setDate(tagDate);
        tagTimeTextView.setText(String.format(
                getResources().getString(R.string.tag_time),
                tagDate.getHourOfDay(),
                tagDate.getMinuteOfHour()));
    }

    @Override
    public void onTagDatePicked(int year, int month, int day) {
        if (!tagView.isReady() || viewMode != ViewMode.EDITING_MODE) {
            return;
        }

        MutableDateTime mutableTagDate = currentlyEditedTag.getDate().toMutableDateTime();
        mutableTagDate.setYear(year);
        mutableTagDate.setMonthOfYear(month);
        mutableTagDate.setDayOfMonth(day);
        DateTime tagDate = mutableTagDate.toDateTime();
        currentlyEditedTag.setDate(tagDate);
        tagDateTextView.setText(String.format(
                getResources().getString(R.string.tag_date),
                tagDate.getDayOfMonth(),
                tagDate.monthOfYear().getAsShortText(),
                tagDate.getYear()));
    }

    // sets the view mode and changes UI accordingly
    private void setViewMode(ViewMode viewMode) {
        this.viewMode = viewMode;
        tagView.setViewMode(viewMode);
        switch (viewMode) {
            case TAGGING_MODE:
                tagButton.setVisibility(View.VISIBLE);
                currentlyEditedTag = null;
                tagInfoLayout.setVisibility(View.INVISIBLE);
                tagView.setPanEnabled(true);
                tagView.setZoomEnabled(true);
                if (tagView.isReady()) {
                    tagView.moveViewBack();
                }
                tagView.setPanLimit(SubsamplingScaleImageView.PAN_LIMIT_CENTER);
                break;
            case EDITING_MODE:
                beeIdTextView.setText(String.format(
                        getResources().getString(R.string.bee_id),
                        currentlyEditedTag.getBeeId()));
                DateTime tagDate = currentlyEditedTag.getDate();
                tagDateTextView.setText(String.format(
                        getResources().getString(R.string.tag_date),
                        tagDate.getDayOfMonth(),
                        tagDate.monthOfYear().getAsShortText(),
                        tagDate.getYear()));
                tagTimeTextView.setText(String.format(
                        getResources().getString(R.string.tag_time),
                        tagDate.getHourOfDay(),
                        tagDate.getMinuteOfHour()));
                detectionIdTextView.setText(String.format(
                        getResources().getString(R.string.tag_detection_id),
                        currentlyEditedTag.getEntryId()));
                tagLabelEditText.setText(currentlyEditedTag.getLabel());
                tagNotesEditText.setText(currentlyEditedTag.getNotes());
                tagButton.setVisibility(View.INVISIBLE);
                textInputDoneButton.setVisibility(View.INVISIBLE);
                tagInfoLayout.setVisibility(View.VISIBLE);
                tagView.setPanLimit(SubsamplingScaleImageView.PAN_LIMIT_OUTSIDE);
                if (tagView.isReady()) {
                    PointF tagCenterInView = new PointF(
                            tagView.getWidth() / 2,
                            (tagView.getHeight() + tagInfoLayout.getHeight()) / 2
                    );
                    int diameter = Math.round(Math.min(
                            tagView.getHeight() - tagInfoLayout.getHeight(),
                            tagView.getWidth()) * 0.95f);
                    tagView.moveViewToTag(
                            currentlyEditedTag,
                            tagCenterInView,
                            diameter);
                }
                tagView.setPanEnabled(false);
                tagView.setZoomEnabled(false);
                break;
        }
    }

    private URL buildUrl() throws JSONException, MalformedURLException {
        // TODO: use Uri.Builder for this
        String address = "http://5631f922.ngrok.io/decode/single";

        JSONArray output = new JSONArray(new String[] {"IDs", "Orientations"});
        HashMap<String, String> params = new HashMap<>();
        params.put("output", output.toString());

        return new URL(address + buildUrlParamsString(params));
    }

    // from a HashMap of key/value pairs, return the URL query string;
    // values are percent-encoded
    private String buildUrlParamsString(HashMap<String, String> params) {
        StringBuilder stringBuilder = new StringBuilder();
        boolean first = true;
        try {
            for (HashMap.Entry<String, String> entry : params.entrySet()) {
                if (!first) {
                    stringBuilder.append("&");
                } else {
                    stringBuilder.append("?");
                    first = false;
                }
                stringBuilder.append(entry.getKey());
                stringBuilder.append("=");
                stringBuilder.append(URLEncoder.encode(entry.getValue(), "UTF-8"));
            }
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }
        return stringBuilder.toString();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (database != null) {
            database.close();
        }
        dao = null;
    }

    @Override
    protected void onRestart() {
        super.onRestart();
        database = Room.databaseBuilder(getApplicationContext(), TagDatabase.class, "beetag-database").build();
        dao = database.getDao();
    }
}
