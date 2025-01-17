<a id="flaky-tests"></a>
<div class="header">
    Flaky tests
</div>
<div class="body">
    <table>
        <tr>
            <th>Test</th>
            <th class="center">Count</th>
        </tr>
        <#list flakyTests as flakyTest>
        <tr>
            <td>
                <a href="https://github.com/keycloak/keycloak/issues/${flakyTest.number?string.computer}">
                    ${flakyTest.package}<br/>
                    ${flakyTest.testClass}#${flakyTest.testMethod}</a>
            </td>
            <td class="center">${flakyTest.count}</td>
        </tr>
        </#list>
    </table>
</div>
