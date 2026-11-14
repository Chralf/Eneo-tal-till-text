import SwiftUI

struct InfoView: View {
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                tipsSection
                contactSection
            }
            .navigationTitle("Information")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Klar") {
                        dismiss()
                    }
                }
            }
        }
    }

    @ViewBuilder
    private var tipsSection: some View {
        tip(
            title: "Innan du spelar in",
            paragraphs: [
                "Informera alltid deltagarna om att mötet spelas in. Det gäller både digitala och fysiska möten. Du får spela in ett möte du själv deltar i, men som anställd i en offentlig verksamhet har din organisation skyldighet att informera de som spelas in.",
                "Säg till exempel:",
                "\"Jag spelar in det här mötet för att skapa anteckningar. Ljudet skickas till vår egen AI-tjänst för bearbetning. Materialet kan bli allmän handling. Säg till om du inte vill att mötet spelas in.\"",
                "Deltagare i ett digitalt möte ser ingen inspelningssymbol, eftersom appen arbetar utanför mötesplattformen. Du behöver därför alltid informera muntligt eller i chatten.",
            ]
        )
        tip(
            title: "Var ljudet finns",
            paragraphs: [
                "Inspelningen sparas på din telefon. Den skickas till den server du har angett i Inställningar först när du trycker på Omvandla till text.",
                "Ljudet gallras automatiskt lokalt efter det antal dagar du anger under Inställningar (högst 30). Filen ligger kvar på telefonen tills den gallras eller du raderar den själv.",
                "Ljudet som skickats till servern hanteras enligt din organisations regler. Appen har ingen kontroll över det materialet. Lokal gallring påverkar inte materialet på servern.",
            ]
        )
        tip(
            title: "Kontrollera resultatet",
            paragraphs: [
                "Transkribering med AI blir inte alltid rätt. Namn, siffror, fackuttryck och överlappande tal blir ofta fel. Läs alltid igenom texten och rätta innan du använder den som underlag för beslut eller anteckningar.",
            ]
        )
        tip(
            title: "Allmän handling",
            paragraphs: [
                "I offentlig verksamhet kan både inspelningar och transkript vara allmänna handlingar och begäras ut. Tänk på det när du spelar in, och när du sparar materialet.",
            ]
        )
        tip(
            title: "Om appen",
            paragraphs: [
                "Appen spelar in ljud lokalt på din enhet. Den har ingen egen server och samlar inte in någon data. Ljudet skickas endast till den adress du själv anger i Inställningar, och din organisation ansvarar för den tjänsten och för hur materialet där hanteras.",
                "Kontakta din organisations dataskyddsombud vid frågor om hur inspelningar får användas.",
            ]
        )
    }

    @ViewBuilder
    private func tip(title: String, paragraphs: [String]) -> some View {
        Section {
            VStack(alignment: .leading, spacing: 10) {
                ForEach(Array(paragraphs.enumerated()), id: \.offset) { _, paragraph in
                    Text(paragraph)
                        .font(.subheadline)
                        .foregroundStyle(.primary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .padding(.vertical, 2)
        } header: {
            Text(title)
        }
    }

    @ViewBuilder
    private var contactSection: some View {
        Section {
            Text("Appen är gratis och fortsätter vara det.")
                .font(.subheadline)
                .fontWeight(.medium)
                .foregroundStyle(.primary)
                .padding(.vertical, 2)

            if let emailURL = URL(string: "mailto:christian@iteca.se?subject=Eneo%20tal-till-text") {
                Link(destination: emailURL) {
                    Label("christian@iteca.se", systemImage: "envelope")
                }
            }
            if let websiteURL = URL(string: "https://iteca.se/eneotaltilltext") {
                Link(destination: websiteURL) {
                    Label("iteca.se/eneotaltilltext", systemImage: "safari")
                }
            }
        } header: {
            Text("Kontakt")
        }
    }
}
