const form = document.getElementById("issueForm");
const message = document.getElementById("message");
const reportedCount = document.getElementById("reportedCount");

async function refreshCount() {
  const response = await fetch("/api/reports");
  if (!response.ok) throw new Error("Could not load reports.");
  const reports = await response.json();
  reportedCount.textContent = reports.length;
}

form.addEventListener("submit", async (event) => {
  event.preventDefault();
  const submit = form.querySelector("button");
  submit.disabled = true;
  message.textContent = "Submitting issue…";

  try {
    const data = new FormData();
    data.append("issueType", document.getElementById("issueType").value);
    data.append("location", document.getElementById("location").value);
    data.append("description", document.getElementById("description").value);
    const response = await fetch("/api/reports", { method: "POST", body: data });
    const result = await response.json();
    if (!response.ok) throw new Error(result.error || "Could not submit the issue.");
    form.reset();
    await refreshCount();
    message.textContent = "Issue reported successfully!";
  } catch (error) {
    message.textContent = error.message;
  } finally {
    submit.disabled = false;
  }
});

refreshCount().catch((error) => { message.textContent = error.message; });
