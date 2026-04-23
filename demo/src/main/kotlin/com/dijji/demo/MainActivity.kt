package com.dijji.demo

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dijji.sdk.Dijji

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    DemoScreen(
                        onCourseStarted = {
                            Dijji.track(
                                "course_started",
                                mapOf("course_id" to "phy_01", "price" to 499)
                            )
                        },
                        onIdentify = {
                            Dijji.identify(
                                "u_demo_42",
                                traits = mapOf("plan" to "pro", "signup_source" to "demo")
                            )
                        },
                        onOpenCourse = {
                            startActivity(Intent(this, CourseActivity::class.java))
                        },
                        onReset = {
                            Dijji.reset()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun DemoScreen(
    onCourseStarted: () -> Unit,
    onIdentify: () -> Unit,
    onOpenCourse: () -> Unit,
    onReset: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Dijji Android SDK", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "visitor_id = ${Dijji.visitorId().take(8)}…",
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(32.dp))
        Button(onClick = onIdentify) { Text("identify()") }
        Spacer(Modifier.height(12.dp))
        Button(onClick = onCourseStarted) { Text("track('course_started')") }
        Spacer(Modifier.height(12.dp))
        Button(onClick = onOpenCourse) { Text("Open CourseActivity (auto screen_view)") }
        Spacer(Modifier.height(12.dp))
        Button(onClick = onReset) { Text("reset()") }
    }
}
