package com.example.autocallmanager

import android.app.Activity
import android.os.Bundle
import android.text.format.DateFormat
import android.widget.*
import java.util.Date
import java.util.Locale

class HistoryActivity : Activity() {

    private lateinit var search: EditText
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }

        root.addView(
            TextView(this).apply {
                text = "History • 1.0.6"
                textSize = 28f
            }
        )

        search = EditText(this).apply {
            hint =
                "Search contact / number / type / result"
        }

        root.addView(search)

        val row =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.HORIZONTAL
            }

        row.addView(
            Button(this).apply {
                text = "SEARCH"
                setOnClickListener {
                    render()
                }
            }
        )

        row.addView(
            Button(this).apply {
                text = "CLEAR"
                setOnClickListener {
                    TaskStore.clearHistory(
                        this@HistoryActivity
                    )
                    render()
                }
            }
        )

        root.addView(row)

        list =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.VERTICAL
            }

        root.addView(
            ScrollView(this).apply {
                addView(list)
            },
            LinearLayout.LayoutParams(
                -1,
                0,
                1f
            )
        )

        setContentView(root)
        render()
    }

    override fun onResume() {
        super.onResume()
        if (::list.isInitialized) {
            render()
        }
    }

    private fun render() {
        list.removeAllViews()

        val q =
            search.text.toString()
                .trim()
                .lowercase(
                    Locale.getDefault()
                )

        val arr =
            TaskStore.history(this)

        var shown = 0

        for (i in arr.length() - 1 downTo 0) {
            val o =
                arr.optJSONObject(i)
                    ?: continue

            val hay =
                buildString {
                    append(
                        o.optString(
                            "number"
                        )
                    )
                    append(' ')
                    append(
                        o.optString(
                            "contactName"
                        )
                    )
                    append(' ')
                    append(
                        o.optString(
                            "eventType"
                        )
                    )
                    append(' ')
                    append(
                        o.optString(
                            "result"
                        )
                    )
                    append(' ')
                    append(
                        o.optString(
                            "liveStatus"
                        )
                    )
                }.lowercase(
                    Locale.getDefault()
                )

            if (
                q.isNotBlank() &&
                !hay.contains(q)
            ) continue

            val time =
                DateFormat.format(
                    "dd/MM/yyyy hh:mm:ss a",
                    Date(
                        o.optLong("time")
                    )
                )

            list.addView(
                TextView(this).apply {
                    text =
                        "$time\n" +
                        "${o.optString("contactName")
                            .ifBlank {
                                o.optString(
                                    "number"
                                )
                            }}\n" +
                        "Type: ${
                            o.optString(
                                "eventType",
                                "EVENT"
                            )
                        }\n" +
                        "Attempt ${
                            o.optInt("attempt")
                        }/${
                            o.optInt(
                                "maxAttempts"
                            )
                        }\n" +
                        "Result: ${
                            o.optString("result")
                        }\n" +
                        "Local state: ${
                            o.optString(
                                "liveStatus"
                            )
                        }\n" +
                        "Group: ${
                            o.optString(
                                "groupName",
                                "Default"
                            )
                        }"
                    textSize = 15f
                    setPadding(
                        0, 14, 0, 14
                    )
                }
            )

            shown++
        }

        if (shown == 0) {
            list.addView(
                TextView(this).apply {
                    text =
                        "No matching history."
                    textSize = 18f
                    setPadding(
                        0, 24, 0, 24
                    )
                }
            )
        }
    }
}
