/** About, Paste SQL and Handover document dialogs. */
export const dialogs = {
  about: {
    title: 'About Amber',
    slogan: 'Maintain the legacy SQL nobody wants to touch.',
    version: 'Version {version}',
    privacy: 'Amber never runs your SQL. Nothing leaves your computer unless you connect a database (metadata only) or an AI provider.',
    versions: 'Versions',
    copyInfo: 'Copy version info',
    copiedInfo: 'Version info copied.',
    copyFailed: 'Could not copy to the clipboard.',
    engine: 'Engine',
    engineOnJava: '{version} on Java {java}',
    notRunning: 'Not running',
    starting: 'Starting…',
    system: 'System',
    checkUpdates: 'Check for updates',
    checkFailed: 'Could not check: {reason}',
    available: 'Amber {version} is available.',
    latest: 'You have the latest version.',
    latestVersion: 'You have the latest version ({version}).',
    downloadPage: 'Open download page',
    links: 'Links',
    project: 'Project on GitHub',
    website: 'Website',
    releases: 'Releases',
    issue: 'Report an issue',
    author: 'Author',
    license: 'License',
    notices: 'Third-party notices',
    noticesLead: 'Licenses of the components bundled with Amber.',
    noticesFailed: 'Could not read the notices: {reason}'
  },
  paste: {
    title: 'Paste SQL',
    lead: 'Paste a script to analyze it. Nothing is executed.',
    name: 'Name',
    text: 'SQL text'
  },
  handover: {
    title: 'Handover document',
    lead: 'What the script does, its objects, data flow, risks and the parts Amber could not read: notes for whoever maintains it next.',
    writing: 'Writing the handover document',
    preview: 'Handover document preview'
  }
}
