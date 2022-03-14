package vn.vndgroup.myapplication

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import coil.load
import vn.vndgroup.myapplication.databinding.ActivityImagePreviewBinding
import java.io.File

class ImagePreviewActivity : AppCompatActivity() {
    private lateinit var binding: ActivityImagePreviewBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityImagePreviewBinding.inflate(layoutInflater)
        setContentView(binding.root)
        getData(intent)
    }

    override fun getIntent(): Intent {
        return super.getIntent()
    }

    fun getData(intent: Intent?) {
        if (intent == null) {
            return
        }
        val url= intent.getStringExtra("data")
        binding.ivPreview.load(File(url!!)) {
            placeholder(R.drawable.test_drawable)
        }
    }

}