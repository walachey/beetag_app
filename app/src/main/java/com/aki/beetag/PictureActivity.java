package com.aki.beetag;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.ThumbnailUtils;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.MediaStore;
import android.support.annotation.NonNull;
import android.support.v4.content.ContextCompat;
import android.support.v4.content.FileProvider;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

public class PictureActivity extends Activity {

    private static final int REQUEST_PERMISSION_EXTERNAL_STORAGE = 0;
    private static final int REQUEST_PERMISSION_CAMERA = 1;
    private static final int REQUEST_PERMISSION_MULTIPLE = 2;
    private static final int REQUEST_CAPTURE_IMAGE = 3;

    private ImageButton cameraButton;
    private GridView imageGridView;

    private HandlerThread backgroundHandlerThread;
    private Handler backgroundHandler;

    private boolean storageWritePermissionGranted;
    private boolean cameraPermissionGranted;

    private File imageFolder;
    private File lastImageFile;
    private File[] imageFiles;

    // ImageAdapter gets images from folder and supplies GridView with ImageViews to display
    private class ImageAdapter extends BaseAdapter {

        private Context context;

        public ImageAdapter(Context c) {
            this.context = c;
        }

        @Override
        public int getCount() {
            return imageFiles.length;
        }

        @Override
        public File getItem(int position) {
            if (position < 0 || position >= imageFiles.length) {
                return null;
            } else {
                return imageFiles[position];
            }
        }

        @Override
        public long getItemId(int i) {
            return 0;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ImageView imageView;
            int thumbnailSize = ((GridView) parent).getColumnWidth();
            Log.d("cameradebug", "thumbnail size: " + thumbnailSize);
            if (convertView == null) {
                imageView = new ImageView(context);
                imageView.setLayoutParams(new GridView.LayoutParams(thumbnailSize, thumbnailSize));
                imageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
                imageView.setPadding(2, 2, 2, 2);
            } else {
                imageView = (ImageView) convertView;
                imageView.setLayoutParams(new GridView.LayoutParams(thumbnailSize, thumbnailSize));
                imageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
                imageView.setPadding(2, 2, 2, 2);
            }
            // TODO limit thumbnail size (250x250?)
            Bitmap thumbnail = ThumbnailUtils.extractThumbnail(BitmapFactory.decodeFile(imageFiles[position].getPath()), thumbnailSize, thumbnailSize);
            imageView.setImageBitmap(thumbnail);
            return imageView;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        checkCameraAndStoragePermissions();

        setContentView(R.layout.activity_picture);

        createImageFolder();
        imageFiles = imageFolder.listFiles();

        imageGridView = findViewById(R.id.gridview_images);
        imageGridView.setAdapter(new ImageAdapter(this));
        imageGridView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                File clickedImage = (File) imageGridView.getItemAtPosition(position);
                Toast.makeText(getApplicationContext(),
                        "(" + position + "/" + imageGridView.getCount() + "): " + clickedImage.toString(),
                        Toast.LENGTH_SHORT).show();
            }
        });
        Log.d("cameradebug", "height: " + getResources().getDisplayMetrics().heightPixels);
        Log.d("cameradebug", "width: " + getResources().getDisplayMetrics().widthPixels);
        //imageGridView.setColumnWidth((int) (getResources().getDisplayMetrics().heightPixels) / 3);

        cameraButton = findViewById(R.id.button_camera);
        cameraButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (!storageWritePermissionGranted) {
                    checkStorageWritePermission();
                    return;
                }
                if (!cameraPermissionGranted) {
                    checkCameraPermission();
                    return;
                }
                dispatchCaptureIntent();
            }
        });
    }

    private void dispatchCaptureIntent() {
        Intent captureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        if (captureIntent.resolveActivity(getPackageManager()) != null) {
            lastImageFile = null;
            try {
                lastImageFile = createImageFile();
            } catch (IOException e) {
                e.printStackTrace();
            }
            if (lastImageFile != null) {
                String authorities = getApplicationContext().getPackageName() + ".fileprovider";
                Uri imageUri = FileProvider.getUriForFile(this, authorities, lastImageFile);
                captureIntent.putExtra(MediaStore.EXTRA_OUTPUT, imageUri);
                startActivityForResult(captureIntent, REQUEST_CAPTURE_IMAGE);
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        switch (requestCode) {
            case REQUEST_CAPTURE_IMAGE:
                if (resultCode == RESULT_OK) {
                    // rename image file so that timestamp is correct
                    try {
                        if (!lastImageFile.renameTo(createImageFile())) {
                            Toast.makeText(this, "Renaming failed, timestamp of image " +
                                    "in 'Beetags' folder may be wrong.", Toast.LENGTH_SHORT).show();
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                } else {
                    // delete image file
                    if (!lastImageFile.delete()) {
                        Toast.makeText(this, "Deletion failed, there may be empty " +
                                "image files in 'Beetags' folder.", Toast.LENGTH_SHORT).show();
                    }
                }
                break;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        startBackgroundThread();
    }

    @Override
    protected void onPause() {
        stopBackgroundThread();
        super.onPause();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        View decorView = getWindow().getDecorView();
        if (hasFocus) {
            decorView.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (grantResults.length <= 0) return;
        switch (requestCode) {
            case REQUEST_PERMISSION_EXTERNAL_STORAGE:
                if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    storageWritePermissionGranted = true;
                }
                break;
            case REQUEST_PERMISSION_CAMERA:
                if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    cameraPermissionGranted = true;
                }
                break;
            case REQUEST_PERMISSION_MULTIPLE:
                for (int i = 0; i < grantResults.length; i++) {
                     if (permissions[i].equals(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                         if (grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                             storageWritePermissionGranted = true;
                         }
                     } else if (permissions[i].equals(Manifest.permission.CAMERA)) {
                         if (grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                             cameraPermissionGranted = true;
                         }
                     }
                }
                break;
        }
    }

    private void startBackgroundThread() {
        backgroundHandlerThread = new HandlerThread("BackgroundThread");
        backgroundHandlerThread.start();
        backgroundHandler = new Handler(backgroundHandlerThread.getLooper());
    }

    private void stopBackgroundThread() {
        backgroundHandlerThread.quitSafely();
        try {
            backgroundHandlerThread.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        backgroundHandlerThread = null;
        backgroundHandler = null;
    }

    private void createImageFolder() {
        File publicPictureFolder = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
        imageFolder = new File(publicPictureFolder, "Beetags");
        if (!imageFolder.exists()) {
            imageFolder.mkdirs();
        }
    }

    private File createImageFile() throws IOException {
        String timestamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(new Date());
        File imgFile = File.createTempFile(timestamp + "_", ".jpg", imageFolder);
        return imgFile;
    }

    private void checkStorageWritePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                    PackageManager.PERMISSION_GRANTED) {
                storageWritePermissionGranted = true;
            } else {
                storageWritePermissionGranted = false;
                if (shouldShowRequestPermissionRationale(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                    Toast.makeText(this, "This app needs access to external storage to store the image files.",
                            Toast.LENGTH_SHORT).show();
                }
                requestPermissions(new String[] {Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_PERMISSION_EXTERNAL_STORAGE);
            }
        } else {
            // in this case we can just act as if permission was granted
            storageWritePermissionGranted = true;
        }
    }

    private void checkCameraPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED) {
                cameraPermissionGranted = true;
            } else {
                cameraPermissionGranted = false;
                if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                    Toast.makeText(this, "If you want to take your own pictures, this app needs " +
                                    "access to the camera.", Toast.LENGTH_SHORT).show();
                }
                requestPermissions(new String[] {Manifest.permission.CAMERA}, REQUEST_PERMISSION_CAMERA);
            }
        } else {
            // in this case we can just act as if permission was granted
            cameraPermissionGranted = true;
        }
    }

    private void checkCameraAndStoragePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            ArrayList<String> permissions = new ArrayList<String>();
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED) {
                cameraPermissionGranted = true;
            } else {
                cameraPermissionGranted = false;
                if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                    Toast.makeText(this, "If you want to take your own pictures, this app needs " +
                            "access to the camera.", Toast.LENGTH_SHORT).show();
                }
                permissions.add(Manifest.permission.CAMERA);
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                    PackageManager.PERMISSION_GRANTED) {
                storageWritePermissionGranted = true;
            } else {
                storageWritePermissionGranted = false;
                if (shouldShowRequestPermissionRationale(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                    Toast.makeText(this, "This app needs access to external storage to store the image files.",
                            Toast.LENGTH_SHORT).show();
                }
                permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            }
            if (!permissions.isEmpty()) {
                requestPermissions(permissions.toArray(new String[0]), REQUEST_PERMISSION_MULTIPLE);
            }
        } else {
            // in this case we can just act as if permission was granted
            cameraPermissionGranted = true;
            storageWritePermissionGranted = true;
        }
    }
}
