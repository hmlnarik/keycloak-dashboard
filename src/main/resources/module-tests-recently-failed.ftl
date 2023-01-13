<a id="recently-failed"></a>
<div class="modal">
    <div class="header">
        Recently failed jobs
    </div>
    <div class="body">
        <table>
            <tr>
                <th>Date</th>
                <th>Run</th>
                <th>Conclusion</th>
                <th>Job</th>
            </tr>
            <#list recentFailedJobs as job>
            <tr>
                <td class="size10">${job.failedRun.date?date}</td>
                <td class="size10"><a href="#failed-run-${job.failedRun.runId}">${job.failedRun.runId}</a></td>
                <td class="size10">${job.conclusion}</td>
                <td><a href="#failed-job-${job.anchor}">${job.name}</a></td>
            </tr>
        </#list>
        </table>
    </div>
</div>